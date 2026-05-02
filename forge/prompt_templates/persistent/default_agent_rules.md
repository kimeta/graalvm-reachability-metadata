Workflow rules:
- Modify only files the workflow made editable.
- Use only the target library's public API unless a prompt explicitly says otherwise.
- Keep generated tests meaningful and avoid replacing real coverage with trivial assertions.
- Keep tests compatible with Native Image by default.
- Do not skip Native Image execution with runtime guards or native-image-specific disables.
- Do not compile, run, or verify tests yourself; the workflow runs validation externally.
- Keep edits focused on the active library and requested workflow task.
