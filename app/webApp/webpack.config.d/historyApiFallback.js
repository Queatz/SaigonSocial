// Single-page app routing for the Kotlin/JS webpack-dev-server (DESIGN §12).
//
// The client mirrors its current screen into the URL ("/", "/room/{id}",
// "/room/{id}/thread/{id}"). Those paths only exist on the client: reloading or deep-linking
// to e.g. "/room/coffee" makes the browser ask the dev server for that path, which has no such
// file and answers "Cannot GET /room/coffee".
//
// `historyApiFallback` tells the dev server to serve index.html for any navigation request that
// doesn't map to a real file, so the app boots and its in-app router (UrlRouter) takes over and
// restores the right screen from the URL. This mirrors the production nginx `try_files` fallback.
config.devServer = config.devServer || {};
config.devServer.historyApiFallback = true;
