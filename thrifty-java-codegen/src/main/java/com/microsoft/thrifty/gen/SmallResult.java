package com.microsoft.thrifty.gen;


public final class SmallResult {
  public final String identifier;

  private SmallResult(String identifier) {
    this.identifier = identifier;
  }

  @Override
  @SuppressWarnings("StringEquality")
  public boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    if (!(other instanceof SmallResult)) return false;
    SmallResult that = (SmallResult) other;
    return (this.identifier == that.identifier || (this.identifier != null && this.identifier.equals(that.identifier)));
  }

  @Override
  public int hashCode() {
    int code = 16777619;
    code ^= (this.identifier == null) ? 0 : this.identifier.hashCode();
    code *= 0x811c9dc5;
    return code;
  }
}

