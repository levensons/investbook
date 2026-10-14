# Turbo Drive

`turbo-8.0.20.js` is the unmodified `dist/turbo.es2017-umd.js` from the official npm package `@hotwired/turbo@8.0.20`.
Package integrity (npm SHA-512):

```
sha512-IilkH/+h92BRLeY/rMMR3MUh1gshIfdra/qZzp/Bl5FmiALD/6sQZK/ecxSbumeyOYiWr/JRI+Au1YQmkJGnoA==
```

The upstream bundle banner says 8.0.19; the published package version is 8.0.20. Its MIT license is included as `turbo-LICENSE.txt`, from https://github.com/hotwired/turbo/blob/v8.0.20/MIT-LICENSE.

Source: https://github.com/hotwired/turbo/tree/v8.0.20

The file is served locally, with no CDN dependency for navigation. To update, use `npm pack @hotwired/turbo@<version>`, verify npm integrity, copy the UMD distribution and license, update all page references and run `src/test/ui/workspace.cjs`. No npm build is required to run Investbook.
