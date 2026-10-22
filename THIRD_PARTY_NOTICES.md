# Third-party notices

myembro is licensed under GPL-3.0-or-later (see [LICENSE](LICENSE)). It contains code
derived from the following projects, whose notices are reproduced as their licenses require.

## pyembroidery

- Source: https://github.com/EmbroidePy/pyembroidery (version 1.5.1)
- Ported files: `engine/.../io/PesReader.kt` (PesReader.py, PecReader.py),
  `engine/.../io/DstReader.kt` (DstReader.py), `engine/.../io/PecThreads.kt` (EmbThreadPec.py),
  `PatternBuilder.interpolateTrims` / `interpolateDuplicateColorAsStop` (EmbPattern.py).
- Changes: translated to Kotlin; bounded PEC block parsing, truncation warnings, streaming
  trim interpolation (see git history).

```
MIT License

Copyright (c) 2018 

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Ink/Stitch (planned)

Digitizing algorithms will be ported from https://github.com/inkstitch/inkstitch
(GPL-3.0-or-later). Each ported file will record its upstream path and commit.
