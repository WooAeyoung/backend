import React from 'react'
import ReactDOM from 'react-dom/client'
import SiteApp from './SiteApp'
import './styles.css'

// Prevent Chrome from restoring a previous pinned-story position before React mounts.
if ('scrollRestoration' in window.history) window.history.scrollRestoration = 'manual'
window.scrollTo(0, 0)

ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><SiteApp /></React.StrictMode>)

