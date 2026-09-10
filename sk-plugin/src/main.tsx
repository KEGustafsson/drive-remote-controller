import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { SkClientContext } from './hooks/useSkConnection';
import { createSkClient } from './skClient';
import './index.css';

// Connects directly to the Signal K server's own stream endpoint,
// same-origin -- this app is served BY that same server (webapps.ts mounts
// it under /<package name>/), so a relative, protocol/host-matched URL is
// correct with no configuration needed. Created once, at module scope, so
// StrictMode's dev-mode double-render can't open a second competing
// connection (see useSkConnection.ts).
const wsProtocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
const client = createSkClient(
  `${wsProtocol}://${window.location.host}/signalk/v1/stream?subscribe=none`,
);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <SkClientContext.Provider value={client}>
      <App />
    </SkClientContext.Provider>
  </StrictMode>,
);
