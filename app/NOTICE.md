# Third-party code

`DHLiveMini.wasm`, `js/DHLiveMini.js`, `js/MiniLive2.js`, `js/MiniMateLoader.js`,
`js/pako.min.js`, and the `assets*/`, `background/` and `common/` folders come from
[kleinlee/DH_live](https://github.com/kleinlee/DH_live) (`web_demo/static`, commit
`4467e97`), released under the MIT License.

The only change to those files: the auto-start `DOMContentLoaded` listener was
removed from `js/MiniMateLoader.js` so that `app.js` controls startup.

The sample avatars carry a "MatesX" mark. Per the DH_live README, commercial use of
the web avatars without the mark requires authorization from matesx.com.
