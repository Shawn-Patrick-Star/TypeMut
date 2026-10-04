from crawl_jbs import JBSDatasetSpec, build_cli, run_jbs_crawler, run_jbs_patch_enricher

# IMPORTANT:
# This is a JIT/compiler *candidate* corpus. "hotspot/compiler" is the
# OpenJDK Compiler team's JBS sampling frame. Final inclusion as an actual
# JIT-triggered, type-related bug is decided later by expert review.
SPEC = JBSDatasetSpec(
    name="HotSpot-JIT-Candidate",
    output_dir="Hotspot_JIT_Bugs_v4",
    component="hotspot",
    subcomponent="compiler",
)

if __name__ == "__main__":
    parser = build_cli(SPEC)
    args = parser.parse_args()
    try:
        if args.patch_only:
            run_jbs_patch_enricher(
                SPEC,
                workers=args.workers,
                max_fixes=args.max_fixes,
            )
        else:
            run_jbs_crawler(
                SPEC,
                start_date=args.start_date,
                end_date=args.end_date,
                window_days=args.window_days,
                page_size=args.page_size,
                workers=args.workers,
                retry_no_source=args.retry_no_source,
                fixes_mode=args.fixes,
                max_fixes=args.max_fixes,
            )
    except KeyboardInterrupt:
        print("\nCrawler interrupted by user.", flush=True)
        raise SystemExit(130)
