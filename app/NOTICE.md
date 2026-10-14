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

## 3D mode

- `vendor/three/` — [three.js](https://threejs.org) r170 (`three.module.min.js`,
  `addons/loaders/GLTFLoader.js`, `addons/utils/BufferGeometryUtils.js`), MIT License.
- `vendor/three-vrm.module.min.js` — [@pixiv/three-vrm](https://github.com/pixiv/three-vrm)
  3.5.5, MIT License (`vendor/three-vrm.LICENSE`).
- `models/avatar.vrm` — `VRM1_Constraint_Twist_Sample.vrm` from the three-vrm examples,
  © 2022 pixiv Inc., [VRM Public License 1.0](https://vrm.dev/licenses/1.0/): redistribution
  and modification allowed, commercial use allowed, credit not required.
