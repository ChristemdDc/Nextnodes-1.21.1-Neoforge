// Static preview server for the NextNodes web panel (visual editing only).
// Mirrors WebPanelServer's routing: "/" -> index.html, "/web/<x>" -> web/<x>.
// There is no backend, so app.js falls into its built-in demo mode (sample data).
const http = require('http');
const fs = require('fs');
const path = require('path');

const WEB = path.join(__dirname, '..', 'src', 'main', 'resources', 'web');
const port = parseInt(process.argv[2] || '8123', 10);

const TYPES = {
  '.html': 'text/html',
  '.css': 'text/css',
  '.js': 'application/javascript',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

function send(res, file) {
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Not found: ' + path.basename(file));
      return;
    }
    res.writeHead(200, {
      'Content-Type': (TYPES[path.extname(file)] || 'text/plain') + '; charset=utf-8',
      'Cache-Control': 'no-cache',
    });
    res.end(data);
  });
}

http.createServer((req, res) => {
  const url = decodeURIComponent(req.url.split('?')[0]);
  if (url === '/' || url === '/index.html') return send(res, path.join(WEB, 'index.html'));
  if (url.startsWith('/web/')) return send(res, path.join(WEB, url.slice('/web/'.length)));
  // /api/* and anything else -> 404 so app.js switches to demo mode.
  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('Not found');
}).listen(port, () => {
  console.log('NextNodes web preview running at http://localhost:' + port + '/');
});
