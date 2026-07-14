#!/bin/sh -e
# Git pre-push hook: block pushing commits that contain secrets.
# Install: cp code-formatting/pre-push.sh .git/hooks/pre-push && chmod +x .git/hooks/pre-push
# Skip once: NO_VERIFY=1 git push ...   (or git push --no-verify)

if [ "$NO_VERIFY" ]; then
    echo 'pre-push checks skipped' 1>&2
    exit 0
fi

if ! command -v gitleaks >/dev/null 2>&1; then
    echo 'warning: gitleaks not installed - skipping pre-push secret scan. Install: brew install gitleaks' 1>&2
    exit 0
fi

# stdin lines: <local ref> <local sha> <remote ref> <remote sha>
while read -r _local_ref local_sha _remote_ref remote_sha; do
    # branch deletion: nothing outgoing
    [ "$local_sha" = "0000000000000000000000000000000000000000" ] && continue
    if [ "$remote_sha" = "0000000000000000000000000000000000000000" ]; then
        # new branch: scan commits not yet on any remote
        range="$local_sha --not --remotes"
    else
        range="$remote_sha..$local_sha"
    fi
    if ! gitleaks git --redact --no-banner --log-opts="$range"; then
        echo 'gitleaks detected a secret in outgoing commits. Push aborted.' 1>&2
        echo 'Fix the commit, or for a false positive record its fingerprint in .gitleaksignore.' 1>&2
        exit 1
    fi
done
exit 0
