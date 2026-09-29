# Yurlib API Contracts

`yurlib-v1.yaml` is the active reviewed OpenAPI 3.1 contract. Maven verification parses it with pinned tooling and compares it with `baseline/yurlib-v1.yaml`; backward-incompatible changes fail the build.

When a contract change is intentional:

1. edit and review the active contract;
2. run `mvn verify` and assess the compatibility report;
3. obtain approval for any breaking change through the repository's contract-review process;
4. only then update the baseline in the same reviewed change.

Do not update the baseline merely to make a failed compatibility check pass.
