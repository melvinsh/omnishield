//! A self-pipe used to interrupt the tunnel loop's `poll()`.
//!
//! The loop used to cap its sleep at 200 ms, so anything that changed state off-thread was
//! noticed within a fifth of a second whether or not it announced itself. That cap is what made
//! an idle tunnel cost five wakeups a second, and removing it means every off-thread event now
//! has to say so explicitly. There are three such producers:
//!
//!   * `Runtime::stop`, which would otherwise not be seen until the next natural wakeup;
//!   * the JNI config/rule setters in `android.rs`, whose whole point is to take effect now;
//!   * the DoH worker, whose answers arrive on an mpsc channel the loop only `try_recv`s —
//!     without a wake, an answer would sit unread until some unrelated packet arrived.
//!
//! Missing any one of those would show up as a hang rather than as slowness, which is why the
//! loop still keeps a long ceiling on its sleep as a self-healing backstop.

use std::io;
use std::os::unix::io::RawFd;

pub struct Waker {
    read: RawFd,
    write: RawFd,
}

impl Waker {
    pub fn new() -> io::Result<Self> {
        let mut fds = [0 as RawFd; 2];
        // `pipe` rather than `pipe2`: the latter is Linux/Android-only, and keeping this
        // portable is what lets the tests below run on the build host instead of needing an
        // emulator. The flags are applied separately for the same reason.
        let rc = unsafe { libc::pipe(fds.as_mut_ptr()) };
        if rc != 0 {
            return Err(io::Error::last_os_error());
        }
        // Non-blocking on both ends: the writer must never stall the DoH thread if the pipe
        // fills, and the reader is drained opportunistically from the poll loop.
        for fd in fds {
            unsafe {
                let flags = libc::fcntl(fd, libc::F_GETFL);
                libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
                libc::fcntl(fd, libc::F_SETFD, libc::FD_CLOEXEC);
            }
        }
        Ok(Self {
            read: fds[0],
            write: fds[1],
        })
    }

    pub fn read_fd(&self) -> RawFd {
        self.read
    }

    /// Nudges the loop. Safe to call from any thread, and cheap enough to call speculatively.
    ///
    /// A full pipe means a wakeup is already queued and unread, so `EAGAIN` is success: the
    /// loop is going to wake regardless. That is also why the byte written is meaningless.
    pub fn wake(&self) {
        let b = [1u8];
        unsafe {
            libc::write(self.write, b.as_ptr() as *const libc::c_void, 1);
        }
    }

    /// Empties the pipe. Called when `poll` reports the read end readable.
    pub fn drain(&self) {
        let mut buf = [0u8; 64];
        loop {
            let n = unsafe { libc::read(self.read, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
            if n <= 0 {
                break;
            }
        }
    }
}

impl Drop for Waker {
    fn drop(&mut self) {
        unsafe {
            libc::close(self.read);
            libc::close(self.write);
        }
    }
}

// The fds are owned for the lifetime of the struct and only ever written to (or drained from
// the single loop thread), so sharing an `Arc<Waker>` across threads is sound.
unsafe impl Send for Waker {}
unsafe impl Sync for Waker {}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    fn readable(fd: RawFd, timeout_ms: i32) -> bool {
        let mut p = libc::pollfd {
            fd,
            events: libc::POLLIN,
            revents: 0,
        };
        let n = unsafe { libc::poll(&mut p as *mut _, 1, timeout_ms) };
        n > 0 && p.revents & libc::POLLIN != 0
    }

    #[test]
    fn starts_quiet() {
        let w = Waker::new().unwrap();
        assert!(!readable(w.read_fd(), 0), "a fresh waker must not report readable");
    }

    #[test]
    fn wake_makes_it_readable_and_drain_clears_it() {
        let w = Waker::new().unwrap();
        w.wake();
        assert!(readable(w.read_fd(), 100));
        w.drain();
        assert!(!readable(w.read_fd(), 0), "drain must leave the pipe empty");
    }

    #[test]
    fn wakes_across_threads() {
        let w = Arc::new(Waker::new().unwrap());
        let w2 = Arc::clone(&w);
        std::thread::spawn(move || {
            std::thread::sleep(std::time::Duration::from_millis(50));
            w2.wake();
        });
        // Would block for the full second if the cross-thread write were not visible.
        assert!(readable(w.read_fd(), 1000));
    }

    #[test]
    fn repeated_wakes_do_not_block() {
        let w = Waker::new().unwrap();
        // Far more than the pipe buffer; each must return rather than stall on a full pipe.
        for _ in 0..200_000 {
            w.wake();
        }
        assert!(readable(w.read_fd(), 0));
    }
}
