public class RateLimitResult {
    private final boolean applicable;
    private final String selectorValue;

    public RateLimitResult(boolean applicable, String selectorValue) {
        this.applicable = applicable;
        this.selectorValue = selectorValue;
    }

    public boolean isApplicable() {
        return applicable;
    }

    public String getSelectorValue() {
        return selectorValue;
    }
}
