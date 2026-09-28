package satisfactory.spi;

/** Timefold's maturity levels (API.md §5). A STABLE model's API is backwards compatible within its version. */
public enum Maturity {
    TEMPLATE, EXPERIMENTAL, PREVIEW, STABLE, DEPRECATED
}
