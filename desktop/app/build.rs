// Gives the Windows build the seal as its file icon.
fn main() {
    #[cfg(windows)]
    {
        let mut res = winres::WindowsResource::new();
        res.set_icon("assets/icon.ico");
        res.compile().expect("couldn't embed the icon");
    }
}
