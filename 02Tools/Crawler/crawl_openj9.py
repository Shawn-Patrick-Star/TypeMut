from crawl_github import (
    GitHubDatasetSpec,
    build_cli,
    run_github_crawler,
    run_github_patch_enricher,
)


# OpenJ9-specific configuration only.
#
# This is a JIT *candidate* corpus. Final decisions about whether an issue is
# genuinely JIT-triggered and type-related are made later by expert review.
SPEC = GitHubDatasetSpec(
    name="OpenJ9-JIT-Candidate",
    repository="eclipse-openj9/openj9",
    output_dir="OpenJ9_JIT_Bugs_v4",
    labels=("comp:jit",),
    issue_prefix="OpenJ9",
)


if __name__ == "__main__":
    parser = build_cli(SPEC)
    args = parser.parse_args()

    try:
        if args.patch_only:
            run_github_patch_enricher(
                SPEC,
                workers=args.workers,
                max_fixes=args.max_fixes,
            )
        else:
            run_github_crawler(
                SPEC,
                start_date=args.start_date,
                end_date=args.end_date,
                window_days=args.window_days,
                workers=args.workers,
                retry_no_source=args.retry_no_source,
                fixes_mode=args.fixes,
                max_fixes=args.max_fixes,
            )
    except KeyboardInterrupt:
        print("\nCrawler interrupted by user.", flush=True)
        raise SystemExit(130)
