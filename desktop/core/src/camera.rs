//! The camera's "Send to Smartphone" mode. The camera runs a UPnP/DLNA media server (usually at
//! 192.168.122.1:64321) and exposes its photos through a standard ContentDirectory. A port of
//! the phone app's CameraClient; here the computer simply joins the camera's Wi-Fi.

use std::io::{self, Read};
use std::net::UdpSocket;
use std::time::{Duration, Instant};

pub const DEFAULT_DESCRIPTION_URL: &str = "http://192.168.122.1:64321/dd.xml";

/// One downloadable rendition of a photo (full size, 2M copy, thumbnail ...).
#[derive(Clone, Debug, PartialEq)]
pub struct Resource {
    pub url: String,
    pub protocol_info: String,
    pub size: Option<u64>,
    pub resolution: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct CameraItem {
    pub id: String,
    pub title: String,
    pub date: Option<String>,
    pub resources: Vec<Resource>,
}

impl CameraItem {
    /// What remembers that this photo was copied already: name and capture time, as on the phone.
    pub fn key(&self) -> String {
        format!("{}|{}", self.title, self.date.as_deref().unwrap_or(""))
    }

    /// The full-size rendition: the largest resource that isn't a thumbnail.
    pub fn best_resource(&self) -> Option<&Resource> {
        self.resources.iter().max_by_key(|r| r.size.unwrap_or_else(|| resolution_pixels(r.resolution.as_deref())))
    }

    /// The camera's "large" preview (DLNA JPEG_LRG): much smaller than the original.
    pub fn small_resource(&self) -> Option<&Resource> {
        self.resources.iter().find(|r| r.protocol_info.contains("JPEG_LRG")).or_else(|| self.best_resource())
    }
}

fn resolution_pixels(r: Option<&str>) -> u64 {
    let Some(r) = r else { return 0 };
    let mut parts = r.split('x').map(|p| p.trim().parse::<u64>().unwrap_or(0));
    parts.next().unwrap_or(0) * parts.next().unwrap_or(0)
}

#[derive(Debug, Default)]
pub struct BrowsePage {
    pub containers: Vec<(String, String)>,
    pub items: Vec<CameraItem>,
    pub total: usize,
}

pub struct CameraClient {
    agent: ureq::Agent,
    pub base_url: String,
    pub name: Option<String>,
    content_directory: String,
    log: Box<dyn Fn(&str) + Send + Sync>,
}

impl CameraClient {
    pub fn new(log: impl Fn(&str) + Send + Sync + 'static) -> Self {
        let agent = ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(10)).timeout_read(Duration::from_secs(60)).build();
        CameraClient {
            agent,
            base_url: "http://192.168.122.1:64321".into(),
            name: None,
            content_directory: "/upnp/control/ContentDirectory".into(),
            log: Box::new(log),
        }
    }

    /// Sends an SSDP M-SEARCH and returns the LOCATION header of the first reply, if any.
    pub fn discover(&self, timeout: Duration) -> io::Result<Option<String>> {
        let msg = b"M-SEARCH * HTTP/1.1\r\nHOST:239.255.255.250:1900\r\nST:upnp:rootdevice\r\nMX:2\r\nMAN:\"ssdp:discover\"\r\n\r\n";
        let socket = UdpSocket::bind("0.0.0.0:0")?;
        for _ in 0..2 {
            socket.send_to(msg, "239.255.255.250:1900")?;
        }
        let deadline = Instant::now() + timeout;
        let mut buf = [0u8; 4096];
        while let Some(left) = deadline.checked_duration_since(Instant::now()).filter(|d| !d.is_zero()) {
            socket.set_read_timeout(Some(left))?;
            let (n, from) = match socket.recv_from(&mut buf) {
                Ok(r) => r,
                Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => break,
                Err(e) => return Err(e),
            };
            (self.log)(&format!("SSDP reply from {}", from.ip()));
            if let Some(location) = ssdp_location(&String::from_utf8_lossy(&buf[..n])) {
                return Ok(Some(location));
            }
        }
        Ok(None)
    }

    /// Reads the device description and finds the ContentDirectory control URL.
    pub fn load_description(&mut self, description_url: &str) -> Result<(), String> {
        let (base, _) = split_base(description_url).ok_or_else(|| format!("bad URL {description_url}"))?;
        self.base_url = base;
        let xml = self.get_text(description_url)?;
        let d = parse_description(&xml);
        if let Some(name) = &d.name {
            (self.log)(&format!("Camera: {name}"));
        }
        self.name = d.name;
        if let Some(c) = d.content_directory {
            self.content_directory = c;
        }
        (self.log)(&format!("ContentDirectory control: {}", self.content_directory));
        Ok(())
    }

    pub fn browse(&self, object_id: &str, start: usize, count: usize) -> Result<BrowsePage, String> {
        let body = format!(
            r#"<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><ObjectID>{}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>{start}</StartingIndex><RequestedCount>{count}</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>"#,
            escape(object_id)
        );
        let url = format!("{}{}", self.base_url, self.content_directory);
        let reply = self
            .agent
            .post(&url)
            .set("Content-Type", "text/xml; charset=\"utf-8\"")
            .set("SOAPAction", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
            .send_string(&body);
        let text = match reply {
            Ok(r) => r.into_string().map_err(|e| e.to_string())?,
            Err(ureq::Error::Status(code, r)) => {
                let t = r.into_string().unwrap_or_default();
                return Err(format!("Browse {object_id} failed: HTTP {code}\n{}", t.chars().take(1000).collect::<String>()));
            }
            Err(e) => return Err(e.to_string()),
        };
        parse_browse(&text)
    }

    /// Walks the whole tree from the root and returns every item found.
    pub fn list_all_items(&self, max_depth: usize) -> Result<Vec<CameraItem>, String> {
        let mut out = Vec::new();
        self.walk("0", "root", 0, max_depth, &mut out)?;
        Ok(out)
    }

    fn walk(&self, id: &str, name: &str, depth: usize, max_depth: usize, out: &mut Vec<CameraItem>) -> Result<(), String> {
        let mut start = 0;
        loop {
            let page = self.browse(id, start, 100)?;
            if start == 0 {
                (self.log)(&format!(
                    "{}[{name}] id={id} total={} containers={} items={}",
                    "  ".repeat(depth),
                    page.total,
                    page.containers.len(),
                    page.items.len()
                ));
            }
            let returned = page.containers.len() + page.items.len();
            out.extend(page.items);
            if depth < max_depth {
                for (cid, ctitle) in &page.containers {
                    self.walk(cid, ctitle, depth + 1, max_depth, out)?;
                }
            }
            start += returned;
            if returned == 0 || start >= page.total {
                return Ok(());
            }
        }
    }

    /// Opens a download; the reader streams the body.
    pub fn open(&self, url: &str) -> Result<Box<dyn Read + Send + Sync>, String> {
        let r = self.agent.get(url).call().map_err(|e| e.to_string())?;
        Ok(r.into_reader())
    }

    fn get_text(&self, url: &str) -> Result<String, String> {
        self.agent.get(url).timeout(Duration::from_secs(20)).call().map_err(|e| e.to_string())?.into_string().map_err(|e| e.to_string())
    }
}

fn ssdp_location(reply: &str) -> Option<String> {
    reply.lines().find_map(|l| {
        let (k, v) = l.split_once(':')?;
        k.trim().eq_ignore_ascii_case("location").then(|| v.trim().to_string())
    })
}

/// "http://host:port/dd.xml" -> ("http://host:port", "/dd.xml").
fn split_base(url: &str) -> Option<(String, String)> {
    let scheme_end = url.find("://")? + 3;
    let path_start = url[scheme_end..].find('/').map(|i| i + scheme_end).unwrap_or(url.len());
    Some((url[..path_start].to_string(), url[path_start..].to_string()))
}

#[derive(Debug, Default, PartialEq)]
struct Description {
    name: Option<String>,
    content_directory: Option<String>,
}

fn parse_description(xml: &str) -> Description {
    let mut d = Description::default();
    let Ok(doc) = roxmltree::Document::parse(xml) else { return d };
    d.name = doc.descendants().find(|n| n.has_tag_name("friendlyName")).and_then(|n| n.text()).map(|t| t.trim().to_string());
    for service in doc.descendants().filter(|n| n.tag_name().name() == "service") {
        let child = |name: &str| service.children().find(|c| c.tag_name().name() == name).and_then(|c| c.text()).map(str::trim);
        if child("serviceType").is_some_and(|t| t.contains("ContentDirectory")) {
            d.content_directory = child("controlURL").map(str::to_string);
        }
    }
    d
}

fn parse_browse(soap: &str) -> Result<BrowsePage, String> {
    let doc = roxmltree::Document::parse(soap).map_err(|e| format!("bad Browse reply: {e}"))?;
    let text = |name: &str| doc.descendants().find(|n| n.tag_name().name() == name).and_then(|n| n.text()).unwrap_or("").to_string();
    let didl = text("Result");
    let total = text("TotalMatches").trim().parse().unwrap_or(0);
    let mut page = parse_didl(&didl);
    page.total = total;
    Ok(page)
}

fn parse_didl(didl: &str) -> BrowsePage {
    let mut page = BrowsePage::default();
    if didl.trim().is_empty() {
        return page;
    }
    let Ok(doc) = roxmltree::Document::parse(didl) else { return page };
    for node in doc.root_element().children().filter(|n| n.is_element()) {
        let id = node.attribute("id").unwrap_or("").to_string();
        let field = |name: &str| node.children().find(|c| c.tag_name().name() == name).and_then(|c| c.text()).map(|t| t.trim().to_string());
        let title = field("title").unwrap_or_default();
        match node.tag_name().name() {
            "container" => page.containers.push((id, title)),
            "item" => {
                let resources = node
                    .children()
                    .filter(|c| c.tag_name().name() == "res")
                    .map(|r| Resource {
                        url: r.text().unwrap_or("").trim().to_string(),
                        protocol_info: r.attribute("protocolInfo").unwrap_or("").to_string(),
                        size: r.attribute("size").and_then(|s| s.parse().ok()),
                        resolution: r.attribute("resolution").map(str::to_string),
                    })
                    .collect();
                page.items.push(CameraItem { id, title, date: field("date"), resources });
            }
            _ => {}
        }
    }
    page
}

fn escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_location_header() {
        let reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.122.1:64321/dd.xml\r\n\r\n";
        assert_eq!(ssdp_location(reply).as_deref(), Some("http://192.168.122.1:64321/dd.xml"));
        assert_eq!(split_base("http://192.168.122.1:64321/dd.xml"), Some(("http://192.168.122.1:64321".into(), "/dd.xml".into())));
    }

    #[test]
    fn finds_the_content_directory() {
        let xml = r#"<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device><friendlyName>ILCE-7M3</friendlyName><serviceList>
<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/upnp/control/ConnectionManager</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/upnp/control/CDS</controlURL></service>
</serviceList></device></root>"#;
        assert_eq!(parse_description(xml), Description { name: Some("ILCE-7M3".into()), content_directory: Some("/upnp/control/CDS".into()) });
    }

    #[test]
    fn parses_a_browse_reply() {
        let didl = r#"<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><container id="03_01" parentID="03" restricted="1"><dc:title>2026-10-19</dc:title></container><item id="04_01_0001" parentID="04_01" restricted="1"><dc:title>DSC04217.JPG</dc:title><dc:date>2026-10-19T16:42:07</dc:date><res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG" resolution="1616x1080">http://192.168.122.1:60151/LRG_DSC04217.JPG</res><res protocolInfo="http-get:*:image/jpeg:*" size="11534336" resolution="6000x4000">http://192.168.122.1:60151/ORG_DSC04217.JPG</res></item></DIDL-Lite>"#;
        let soap = format!(
            r#"<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><Result>{}</Result><NumberReturned>2</NumberReturned><TotalMatches>2</TotalMatches></u:BrowseResponse></s:Body></s:Envelope>"#,
            escape(didl)
        );
        let page = parse_browse(&soap).unwrap();
        assert_eq!(page.total, 2);
        assert_eq!(page.containers, vec![("03_01".to_string(), "2026-10-19".to_string())]);
        let item = &page.items[0];
        assert_eq!(item.title, "DSC04217.JPG");
        assert_eq!(item.key(), "DSC04217.JPG|2026-10-19T16:42:07");
        assert!(item.best_resource().unwrap().url.ends_with("ORG_DSC04217.JPG"));
        assert!(item.small_resource().unwrap().url.ends_with("LRG_DSC04217.JPG"));
    }
}
