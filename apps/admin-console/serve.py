#!/usr/bin/env python3
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
import os

os.chdir(Path(__file__).resolve().parent)
ThreadingHTTPServer(("0.0.0.0", int(os.getenv("PORT", "13000"))), SimpleHTTPRequestHandler).serve_forever()
