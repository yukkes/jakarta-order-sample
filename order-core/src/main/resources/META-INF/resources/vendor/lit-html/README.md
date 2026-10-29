# lit-html (vendored)

- Version: 3.3.3
- Source: https://www.npmjs.com/package/lit-html (npm tarball)
- License: BSD-3-Clause (see LICENSE)

Only the files needed at runtime are copied here; they are served as native ES modules,
so the UI needs no bundler or build step.

```text
lit-html.js
directive.js
directive-helpers.js
directives/repeat.js
```

To upgrade, download the new tarball and copy the same files (plus any new files they
import). Do not add `development/` builds or source maps.
