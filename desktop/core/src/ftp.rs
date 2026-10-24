//! Upload-only FTP server for the camera's "FTP Transfer" function (firmware 4.00+), which pushes
//! RAW and JPEG files as they are shot. It accepts any login and stores every STOR through a
//! [UploadSink]; listings are always empty and directories are pretend, since the camera only
//! needs uploads to succeed. Plain FTP only: Secure Protocol has to be Off on the camera.
//! A port of the phone app's FtpServer.

use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Shutdown, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

/// Where uploaded files end up.
pub trait UploadSink: Send + Sync {
    /// Starts a file called `name`; the server writes to it, then commits or aborts.
    fn open(&self, name: &str) -> io::Result<Box<dyn Upload>>;
}

pub trait Upload: Write + Send {
    fn commit(self: Box<Self>) -> io::Result<()>;
    fn abort(self: Box<Self>);
}

pub type Log = Arc<dyn Fn(&str) + Send + Sync>;
pub type OnReceived = Arc<dyn Fn(&str, u64) + Send + Sync>;

pub struct FtpServer {
    stop: Arc<AtomicBool>,
    pub port: u16,
}

impl FtpServer {
    /// Listens on `port` on every interface (0 picks a free one).
    pub fn start(port: u16, sink: Arc<dyn UploadSink>, log: Log, on_received: OnReceived) -> io::Result<FtpServer> {
        let listener = TcpListener::bind(SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), port))?;
        let port = listener.local_addr()?.port();
        listener.set_nonblocking(true)?;
        let stop = Arc::new(AtomicBool::new(false));
        let stopped = stop.clone();
        log(&format!("FTP: listening on port {port}"));
        thread::Builder::new().name("ftp-accept".into()).spawn(move || {
            while !stopped.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((client, _)) => {
                        let (sink, log, on_received) = (sink.clone(), log.clone(), on_received.clone());
                        let _ = thread::Builder::new().name("ftp-session".into()).spawn(move || Session::new(client, sink, log, on_received).run());
                    }
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => thread::sleep(Duration::from_millis(100)),
                    Err(_) => thread::sleep(Duration::from_millis(100)),
                }
            }
        })?;
        Ok(FtpServer { stop, port })
    }

    pub fn stop(&self) {
        self.stop.store(true, Ordering::Relaxed);
    }
}

impl Drop for FtpServer {
    fn drop(&mut self) {
        self.stop();
    }
}

struct Session {
    control: TcpStream,
    sink: Arc<dyn UploadSink>,
    log: Log,
    on_received: OnReceived,
    cwd: String,
    passive: Option<TcpListener>,
    active: Option<SocketAddr>,
    peer: String,
}

impl Session {
    fn new(control: TcpStream, sink: Arc<dyn UploadSink>, log: Log, on_received: OnReceived) -> Self {
        let peer = control.peer_addr().map(|a| a.ip().to_string()).unwrap_or_default();
        Session { control, sink, log, on_received, cwd: "/".into(), passive: None, active: None, peer }
    }

    fn run(mut self) {
        (self.log)(&format!("FTP: {} connected", self.peer));
        if let Err(e) = self.serve() {
            (self.log)(&format!("FTP: {} session ended: {e}", self.peer));
        }
        let _ = self.control.shutdown(Shutdown::Both);
        (self.log)(&format!("FTP: {} disconnected", self.peer));
    }

    fn serve(&mut self) -> io::Result<()> {
        let _ = self.control.set_nonblocking(false);
        self.control.set_read_timeout(Some(Duration::from_secs(5 * 60)))?;
        let mut reader = BufReader::new(self.control.try_clone()?);
        self.reply("220 Shootback ready")?;
        let mut line = String::new();
        loop {
            line.clear();
            if reader.read_line(&mut line)? == 0 {
                return Ok(());
            }
            let line = line.trim_end_matches(['\r', '\n']);
            let (cmd, arg) = match line.split_once(' ') {
                Some((c, a)) => (c.to_ascii_uppercase(), a.trim().to_string()),
                None => (line.to_ascii_uppercase(), String::new()),
            };
            if cmd != "PASS" {
                (self.log)(&format!("FTP: > {line}"));
            }
            if !self.handle(&cmd, &arg)? {
                return Ok(());
            }
        }
    }

    /// Returns false to end the session.
    fn handle(&mut self, cmd: &str, arg: &str) -> io::Result<bool> {
        match cmd {
            "USER" => self.reply("331 Password please")?,
            "PASS" => self.reply("230 Logged in")?,
            "SYST" => self.reply("215 UNIX Type: L8")?,
            "FEAT" => self.reply("211-Features:\r\n EPSV\r\n PASV\r\n UTF8\r\n211 End")?,
            "OPTS" | "TYPE" | "MODE" | "STRU" | "NOOP" => self.reply("200 OK")?,
            "PWD" | "XPWD" => self.reply(&format!("257 \"{}\"", self.cwd))?,
            "CWD" | "XCWD" => {
                self.cwd = self.resolve(arg);
                self.reply("250 OK")?
            }
            "CDUP" | "XCUP" => {
                let trimmed = self.cwd.trim_end_matches('/');
                self.cwd = match trimmed.rfind('/') {
                    Some(0) | None => "/".into(),
                    Some(i) => trimmed[..i].into(),
                };
                self.reply("250 OK")?
            }
            "MKD" | "XMKD" => self.reply(&format!("257 \"{}\" created", self.resolve(arg)))?,
            "RMD" | "XRMD" | "DELE" => self.reply("250 OK")?,
            "ALLO" => self.reply("202 Not needed")?,
            "PASV" => self.open_passive(false)?,
            "EPSV" => self.open_passive(true)?,
            "PORT" => self.set_active(arg)?,
            "LIST" | "NLST" | "MLSD" => self.send_empty_listing()?,
            "STOR" | "APPE" => self.receive(arg)?,
            "SIZE" | "MDTM" => self.reply("550 No such file")?,
            "RNFR" => self.reply("350 Ready")?,
            "RNTO" => {
                (self.log)(&format!("FTP: rename to {arg} ignored"));
                self.reply("250 OK")?
            }
            "QUIT" => {
                self.reply("221 Bye")?;
                return Ok(false);
            }
            _ => self.reply("502 Not implemented")?,
        }
        Ok(true)
    }

    fn resolve(&self, path: &str) -> String {
        if path.is_empty() {
            self.cwd.clone()
        } else if path.starts_with('/') {
            path.to_string()
        } else {
            format!("{}/{}", self.cwd.trim_end_matches('/'), path)
        }
    }

    fn open_passive(&mut self, extended: bool) -> io::Result<()> {
        self.close_data();
        // Bind to the address the camera reached us on, so the reply points at the right network.
        let local = self.control.local_addr()?.ip();
        let listener = TcpListener::bind(SocketAddr::new(local, 0))?;
        let p = listener.local_addr()?.port();
        self.passive = Some(listener);
        if extended {
            self.reply(&format!("229 Entering Extended Passive Mode (|||{p}|)"))
        } else {
            let ip = local.to_string().replace('.', ",");
            self.reply(&format!("227 Entering Passive Mode ({ip},{},{})", p >> 8, p & 0xff))
        }
    }

    fn set_active(&mut self, arg: &str) -> io::Result<()> {
        let parts: Vec<u8> = arg.split(',').filter_map(|p| p.trim().parse().ok()).collect();
        if parts.len() != 6 {
            return self.reply("501 Bad PORT");
        }
        self.close_data();
        let ip = Ipv4Addr::new(parts[0], parts[1], parts[2], parts[3]);
        self.active = Some(SocketAddr::new(IpAddr::V4(ip), parts[4] as u16 * 256 + parts[5] as u16));
        self.reply("200 PORT OK")
    }

    fn open_data(&mut self) -> io::Result<Option<TcpStream>> {
        if let Some(listener) = self.passive.take() {
            // TcpListener has no accept timeout; poll so a camera that never connects can't hang us.
            listener.set_nonblocking(true)?;
            for _ in 0..300 {
                match listener.accept() {
                    Ok((s, from)) => {
                        s.set_nonblocking(false)?;
                        (self.log)(&format!("FTP: data connection from {}", from.ip()));
                        return Ok(Some(s));
                    }
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => thread::sleep(Duration::from_millis(100)),
                    Err(e) => return Err(e),
                }
            }
            return Ok(None);
        }
        if let Some(addr) = self.active.take() {
            return TcpStream::connect_timeout(&addr, Duration::from_secs(15)).map(Some);
        }
        Ok(None)
    }

    fn send_empty_listing(&mut self) -> io::Result<()> {
        self.reply("150 Listing")?;
        match self.open_data() {
            Ok(Some(data)) => {
                let _ = data.shutdown(Shutdown::Both);
                self.reply("226 Done")
            }
            _ => self.reply("425 No data connection"),
        }
    }

    fn receive(&mut self, arg: &str) -> io::Result<()> {
        let name = arg.rsplit('/').next().unwrap_or("").trim().to_string();
        if name.is_empty() {
            return self.reply("501 No file name");
        }
        self.reply(&format!("150 Ready for {name}"))?;
        let mut data = match self.open_data() {
            Ok(Some(d)) => d,
            Ok(None) => return self.reply("425 No data connection"),
            Err(e) => {
                (self.log)(&format!("FTP: no data connection for {name}: {e}"));
                return self.reply("425 No data connection");
            }
        };
        let mut upload = match self.sink.open(&name) {
            Ok(u) => u,
            Err(e) => {
                (self.log)(&format!("FTP: can't store {name}: {e}"));
                let _ = data.shutdown(Shutdown::Both);
                return self.reply("451 Can't store file");
            }
        };
        data.set_read_timeout(Some(Duration::from_secs(60)))?;
        let mut bytes = 0u64;
        let mut buf = vec![0u8; 64 * 1024];
        let copied: io::Result<()> = (|| loop {
            let n = data.read(&mut buf)?;
            if n == 0 {
                return Ok(());
            }
            upload.write_all(&buf[..n])?;
            bytes += n as u64;
        })();
        if let Err(e) = copied.and_then(|_| upload.flush()) {
            (self.log)(&format!("FTP: {name} failed after {bytes} bytes: {e}"));
            upload.abort();
            let _ = data.shutdown(Shutdown::Both);
            return self.reply("426 Transfer aborted");
        }
        if let Err(e) = upload.commit() {
            (self.log)(&format!("FTP: can't store {name}: {e}"));
            return self.reply("451 Can't store file");
        }
        (self.log)(&format!("FTP: received {name} ({} KB)", bytes / 1024));
        (self.on_received)(&name, bytes);
        self.reply("226 Stored")
    }

    fn close_data(&mut self) {
        self.passive = None;
        self.active = None;
    }

    fn reply(&mut self, text: &str) -> io::Result<()> {
        (self.log)(&format!("FTP: < {}", text.split('\r').next().unwrap_or("")));
        self.control.write_all(format!("{text}\r\n").as_bytes())?;
        self.control.flush()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    #[derive(Default)]
    struct Memory(Mutex<Vec<(String, Vec<u8>)>>);

    struct MemUpload<'a>(&'a Memory, String, Vec<u8>);

    impl Write for MemUpload<'static> {
        fn write(&mut self, b: &[u8]) -> io::Result<usize> {
            self.2.extend_from_slice(b);
            Ok(b.len())
        }
        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    impl Upload for MemUpload<'static> {
        fn commit(self: Box<Self>) -> io::Result<()> {
            self.0 .0.lock().unwrap().push((self.1, self.2));
            Ok(())
        }
        fn abort(self: Box<Self>) {}
    }

    struct Sink(&'static Memory);

    impl UploadSink for Sink {
        fn open(&self, name: &str) -> io::Result<Box<dyn Upload>> {
            Ok(Box::new(MemUpload(self.0, name.to_string(), Vec::new())))
        }
    }

    fn line(r: &mut BufReader<TcpStream>) -> String {
        let mut s = String::new();
        r.read_line(&mut s).unwrap();
        s
    }

    #[test]
    fn stores_a_passive_upload() {
        let memory: &'static Memory = Box::leak(Box::default());
        let server = FtpServer::start(0, Arc::new(Sink(memory)), Arc::new(|_| {}), Arc::new(|_, _| {})).unwrap();
        let mut c = TcpStream::connect(("127.0.0.1", server.port)).unwrap();
        let mut r = BufReader::new(c.try_clone().unwrap());
        assert!(line(&mut r).starts_with("220"));
        for cmd in ["USER camera", "PASS x", "TYPE I", "CWD /DCIM"] {
            write!(c, "{cmd}\r\n").unwrap();
            line(&mut r);
        }
        write!(c, "EPSV\r\n").unwrap();
        let reply = line(&mut r);
        let port: u16 = reply.split("|||").nth(1).unwrap().trim_end_matches(|ch: char| !ch.is_ascii_digit()).parse().unwrap();
        write!(c, "STOR /DCIM/DSC04217.ARW\r\n").unwrap();
        let mut data = TcpStream::connect(("127.0.0.1", port)).unwrap();
        assert!(line(&mut r).starts_with("150"));
        data.write_all(b"raw bytes").unwrap();
        drop(data);
        assert!(line(&mut r).starts_with("226"));
        assert_eq!(memory.0.lock().unwrap().as_slice(), &[("DSC04217.ARW".to_string(), b"raw bytes".to_vec())]);
    }
}
