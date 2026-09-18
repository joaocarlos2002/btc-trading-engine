// `npm start` (ng serve on :4200) against the backend on :8080, without CORS (issue #134).
// Only the server's paths are forwarded; /login and every other route is the Angular app itself.
const target = 'http://localhost:8080';

export default {
  '/api': { target, secure: false },
  '/actuator': { target, secure: false },
  '/ws': {
    target,
    ws: true,
    secure: false,
    // The WebSocket only accepts dashboard.allowed.origins (issue #66); present the backend's own origin.
    configure: (proxy) => {
      proxy.on('proxyReqWs', (proxyRequest) => proxyRequest.setHeader('origin', target));
    },
  },
};
