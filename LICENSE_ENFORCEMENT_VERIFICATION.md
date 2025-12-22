# License Header Enforcement Verification

This document demonstrates that the Apache License header enforcement is working correctly.

## Test 1: Auto-apply license headers (Dev Profile)

When compiling with the dev profile (default), Spotless automatically adds license headers to new Java files:

```bash
# Create a new Java file without a license header
cat > core/src/main/java/io/netty/loom/TestFile.java << 'EOF'
package io.netty.loom;

public class TestFile {
    public static void main(String[] args) {
        System.out.println("Test");
    }
}
EOF

# Run spotless:apply (or compile with dev profile)
mvn spotless:apply -pl core
```

**Result**: The license header is automatically added to the file.

**Output from Spotless**:
```
[INFO] clean file: /home/runner/.../core/src/main/java/io/netty/loom/TestFile.java
[INFO] Spotless.Java is keeping 9 files clean - 1 were changed to be clean, 8 were already clean
```

**File after auto-apply**:
```java
/*
 * Copyright 2025 The Netty VirtualThread Scheduler Project
 *
 * The Netty VirtualThread Scheduler Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package io.netty.loom;

public class TestFile {
    public static void main(String[] args) {
        System.out.println("Test");
    }
}
```

## Test 2: Detect missing license headers (CI Profile)

When running with `-P '!dev'` (like in CI), Spotless checks for missing headers and fails the build:

```bash
# Create a new Java file without a license header
cat > core/src/main/java/io/netty/loom/TestFile.java << 'EOF'
package io.netty.loom;

public class TestFile {
    // Missing header
}
EOF

# Run spotless:check without dev profile
mvn spotless:check -pl core -P '!dev'
```

**Result**: Build fails with clear error message showing the missing header.

**Error output**:
```
[ERROR] > The following files had format violations:
[ERROR]     src/main/java/io/netty/loom/TestFile.java
[ERROR]         @@ -1,5 +1,19 @@
[ERROR]         +/*
[ERROR]         +·*·Copyright·2025·The·Netty·VirtualThread·Scheduler·Project
[ERROR]         +·*
[ERROR]         +·*·The·Netty·VirtualThread·Scheduler·Project·licenses·this·file·to·you·under·the·Apache·License,
[ERROR]         +·*·version·2.0·(the·"License");·you·may·not·use·this·file·except·in·compliance·with·the
[ERROR]         +·*·License.·You·may·obtain·a·copy·of·the·License·at:
[ERROR]         +·*
[ERROR]         +·*···https://www.apache.org/licenses/LICENSE-2.0
[ERROR]         +·*
[ERROR]         +·*·Unless·required·by·applicable·law·or·agreed·to·in·writing,·software·distributed·under·the
[ERROR]         +·*·License·is·distributed·on·an·"AS·IS"·BASIS,·WITHOUT·WARRANTIES·OR·CONDITIONS·OF·ANY·KIND,
[ERROR]         +·*·either·express·or·implied.·See·the·License·for·the·specific·language·governing·permissions
[ERROR]         +·*·and·limitations·under·the·License.
[ERROR]         +·*/
[ERROR]          package·io.netty.loom;
[ERROR] Run 'mvn spotless:apply' to fix these violations.
```

## Summary

✅ **Auto-apply works**: New Java files get license headers automatically when using `mvn compile` (dev profile) or `mvn spotless:apply`

✅ **Enforcement works**: Missing license headers are detected and cause build failures in CI via `mvn verify` (which runs `spotless:check`)

✅ **All existing files**: All 13 existing Java files in the project now have proper Apache License headers
