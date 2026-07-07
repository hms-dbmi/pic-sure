#!/bin/sh -e
CWD=$(pwd)
cd $(git rev-parse --show-toplevel)
format_cmd=""

# skip if NO_VERIFY env var set
if [ "$NO_VERIFY" ]; then
    echo 'pre-commit checks skipped' 1>&2
    exit 0
fi

# --- Secret scanning (gitleaks) -------------------------------------------
# Abort the commit if a secret is detected in the staged changes.
if command -v gitleaks >/dev/null 2>&1; then
    if ! gitleaks git --staged --redact --no-banner; then
        echo '✖ gitleaks detected a secret in staged changes. Commit aborted.' 1>&2
        echo '  Remove the secret, or if it is a false positive add a "gitleaks:allow" trailing comment' 1>&2
        echo '  on the line, or record its fingerprint in .gitleaksignore.' 1>&2
        cd "$CWD"
        exit 1
    fi
else
    echo '⚠ gitleaks not installed — skipping secret scan. Install: brew install gitleaks' 1>&2
fi

# --- Block binary blobs and oversized files -------------------------------
STAGED=$(git diff --staged --name-only --diff-filter=ACMR)
JAVABIN=$(printf '%s\n' "$STAGED" | grep -i '\.javabin$' || true)
if [ -n "$JAVABIN" ]; then
    echo '✖ Refusing to commit .javabin data file(s):' 1>&2
    printf '    %s\n' $JAVABIN 1>&2
    echo '  HPDS .javabin blobs are build/data artifacts and must not be committed.' 1>&2
    echo '  Unstage them: git reset HEAD <file>' 1>&2
    cd "$CWD"; exit 1
fi

MAX_BYTES=$((5 * 1024 * 1024))   # 5 MB
LARGE=""
for f in $STAGED; do
    sz=$(git cat-file -s ":$f" 2>/dev/null || echo 0)
    if [ "$sz" -gt "$MAX_BYTES" ]; then
        LARGE="$LARGE    $f ($sz bytes)\n"
    fi
done
if [ -n "$LARGE" ]; then
    echo '✖ Refusing to commit file(s) larger than 5 MB:' 1>&2
    printf '%b' "$LARGE" 1>&2
    echo '  Use Git LFS, or bypass intentionally with: NO_VERIFY=1 git commit ...' 1>&2
    cd "$CWD"; exit 1
fi

# --- Flyway migration immutability ----------------------------------------
# Applied migrations are immutable; changing one breaks Flyway checksum validation.
CHANGED_MIGRATIONS=$(git diff --staged --name-only --diff-filter=MRD | grep -E '(^|/)V[0-9][^/]*__[^/]*\.sql$' || true)
if [ -n "$CHANGED_MIGRATIONS" ]; then
    echo '✖ Refusing to modify/delete existing Flyway migration(s):' 1>&2
    printf '    %s\n' $CHANGED_MIGRATIONS 1>&2
    echo '  Create a NEW V<version>__<description>.sql migration instead of editing an applied one.' 1>&2
    cd "$CWD"; exit 1
fi

# --- Focused tests / debug leftovers --------------------------------------
# Only inspects lines ADDED in this commit, so pre-existing code is unaffected.
ADDED=$(git diff --staged --diff-filter=ACMR -U0 | grep -E '^\+[^+]' || true)
DEBUG_HITS=$(printf '%s\n' "$ADDED" | grep -E 'System\.(out|err)\.println|\.printStackTrace\(|(describe|context|it|test)\.only\(|fdescribe\(|fit\(' || true)
if [ -n "$DEBUG_HITS" ]; then
    echo '✖ Debug / focused-test markers introduced in staged changes:' 1>&2
    printf '%s\n' "$DEBUG_HITS" | sed 's/^/    /' 1>&2
    echo '  Remove stray println/printStackTrace or .only-focused tests,' 1>&2
    echo '  or bypass intentionally with: NO_VERIFY=1 git commit ...' 1>&2
    cd "$CWD"; exit 1
fi

# I'm not great at bash, so this is a bit ugly, but I'll explain each pipe
# 1. Get all staged files
# 2. Reduce to just .java files
# 3. Replace newlines with commas (this was really hard to do in sed)
# 4. Replace commas with $,^.*
# 5. Crop off the last 4 chars
# This results in foo.java$,^.*bar.java$,^.*baz.java$
# I then append ^.* to the beginning of that.
STAGED_JAVA_FILES_AS_REGEX=$(git diff --staged --name-only --diff-filter=ACMR | grep '.java$' | tr '\n' ',' | sed -e 's/,/$,^.*/g' | sed 's/.\{4\}$//')
FILES_TO_RESTAGE=$(git diff --staged --name-only --diff-filter=ACMR)
if [ -n "$STAGED_JAVA_FILES_AS_REGEX" ]; then
   echo "Found the following staged java files to format: $STAGED_JAVA_FILES_AS_REGEX"
   mvn spotless:apply -DspotlessFiles=^.*$STAGED_JAVA_FILES_AS_REGEX
   git add $FILES_TO_RESTAGE
fi

cd $CWD