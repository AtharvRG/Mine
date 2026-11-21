# Third-Party Licenses

This project incorporates components from the following third-party open source projects.
All dependencies are compatible with the GNU General Public License v3.0 (GPL-3.0-or-later) per [Blueprint 94](docs/blueprints/94-licensing.md).

## Production Dependencies

| Component | Version | License | Link |
|---|---|---|---|
| **Kotlin Standard Library & Coroutines** (`kotlinx-coroutines-core`) | 1.9.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| **Kotlin Serialization** (`kotlinx-serialization-json`) | 1.7.3 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| **AndroidX Core KTX** (`androidx.core:core-ktx`) | 1.15.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/core |
| **AndroidX Lifecycle** (`androidx.lifecycle:lifecycle-runtime-ktx`) | 2.8.6 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle |
| **AndroidX Activity Compose** (`androidx.activity:activity-compose`) | 1.9.2 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/activity |
| **Jetpack Compose** (`androidx.compose.ui:ui`, `material3`) | Compose BOM 2026.09.00 | Apache-2.0 | https://developer.android.com/jetpack/compose |
| **AndroidX SQLite** (`androidx.sqlite:sqlite`) | 2.4.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/sqlite |
| **SQLCipher for Android** (`net.zetetic:sqlcipher-android`) | 4.19.1 | BSD-style / Zetetic License | https://www.zetetic.net/sqlcipher/ |
| **Bouncy Castle Cryptography** (`org.bouncycastle:bcprov-jdk18on`) | 1.78.1 | Bouncy Castle Licence (MIT-style) | https://www.bouncycastle.org/licence.html |
| **Jsoup Java HTML Parser** (`org.jsoup:jsoup`) | 1.18.3 | MIT License | https://jsoup.org/ |
| **OkHttp HTTP Client** (`com.squareup.okhttp3:okhttp`) | 4.12.0 | Apache-2.0 | https://square.github.io/okhttp/ |
| **Microsoft ONNX Runtime Android** (`com.microsoft.onnxruntime:onnxruntime-android`) | 1.27.0 | MIT License | https://onnxruntime.ai/ |

## Testing Dependencies

| Component | Version | License | Link |
|---|---|---|---|
| **JUnit 4** (`junit:junit`) | 4.13.2 | Eclipse Public License 1.0 (EPL-1.0) | https://junit.org/junit4/ |
| **AndroidX Test** (`ext:junit`, `runner`, `tracing`) | 1.2.1 / 1.6.2 / 1.2.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/test |
| **Compose UI Test** (`androidx.compose.ui:ui-test-junit4`) | BOM 2026.09.00 | Apache-2.0 | https://developer.android.com/jetpack/compose |

## License Texts & Attributions

### SQLCipher Attribution Notice
Copyright (c) 2008-2024, ZETETIC LLC  
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
* Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
* Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
* Neither the name of the ZETETIC LLC nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY ZETETIC LLC ''AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL ZETETIC LLC BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

### Bouncy Castle Licence
Copyright (c) 2000-2024 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

### Jsoup MIT License
Copyright (c) 2009-2024 Jonathan Hedley (https://jsoup.org/)

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
