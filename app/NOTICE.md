# Third-party code

`DHLiveMini.wasm`, `js/DHLiveMini.js`, `js/MiniLive2.js`, `js/MiniMateLoader.js`,
`js/pako.min.js`, and the `assets*/`, `background/` and `common/` folders come from
[kleinlee/DH_live](https://github.com/kleinlee/DH_live) (`web_demo/static`, commit
`4467e97`), released under the MIT License.

Changes to those files:
- `js/MiniMateLoader.js`: the auto-start `DOMContentLoaded` listener was removed so
  that `app.js` controls startup.
- `js/MiniLive2.js`: the green-screen shader chokes the matte and fully removes green
  spill on edge pixels, to get rid of the green halo around the person.

The sample avatars carry a "MatesX" mark. Per the DH_live README, commercial use of
the web avatars without the mark requires authorization from matesx.com.
