import http.server
import threading
import socketserver
import os
from os.path import join as pjoin 

cloud_init_port = 3003
config_dir = pjoin(
    # "firesim/deploy/vm-cloud-init-configs"
    os.path.dirname(os.path.abspath(__file__)), "..", "vm-cloud-init-configs"
)

if not os.path.isdir(config_dir):
    raise FileNotFoundError(f"Directory {config_dir} does not exist")

# https://stackoverflow.com/questions/39801718/how-to-run-a-http-server-which-serves-a-specific-path
class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=config_dir, **kwargs)

# shutdown: https://stackoverflow.com/questions/17550389/shut-down-socketserver-on-sig
cloud_init_server = socketserver.ThreadingTCPServer(("", cloud_init_port), Handler)
# rootLogger.info(f"Serving Ubuntu autoinstall files at http://localhost:{cloud_init_port}/")

cloud_init_thread = threading.Thread(
    target=cloud_init_server.serve_forever, daemon=True
)

cloud_init_thread.start()

try:
    cloud_init_thread.join()  # blocks forever
except KeyboardInterrupt:
    print("Shutting down cloud-init server...")
    cloud_init_server.shutdown()
    cloud_init_server.server_close()

# close http server - done with initial setup
# cloud_init_server.shutdown()
# cloud_init_server.server_close()
# cloud_init_thread.join()
# rootLogger.info("Closed HTTP server")