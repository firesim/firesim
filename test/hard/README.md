This is a hard-coded img/bin test. You would typically do this while actively
developing a workload or goofing around with something temporarirly. You should typically
not hard-code img and bin paths to avoid creating unreproducible images.

*WARNING*: you should NOT include any other options in a hard-coded config like
this. It's possible that strange errors could occur or your image/binary could
be overwritten if you specify other options than: "name", "img", and "bin".

# How the test works
You should first manually build command.json (this choice was arbitrary). After
that you can build hard.json and the host-init will copy over the images. The
output will be identical to that of command.json.
