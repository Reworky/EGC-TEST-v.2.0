import os, http.server, socketserver
os.chdir('/Users/olegdanilov/Desktop/egc-landing')
PORT = 3456
with socketserver.TCPServer(("", PORT), http.server.SimpleHTTPRequestHandler) as httpd:
    httpd.serve_forever()
