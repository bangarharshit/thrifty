package com.microsoft.thrifty.gen;

public enum  RestMethodResolver {
  GET(retrofit2.http.GET.class),
  DELETE(retrofit2.http.DELETE.class),
  PUT(retrofit2.http.PUT.class),
  POST(retrofit2.http.POST.class);

  private final Class tClass;

  RestMethodResolver(Class tClass) {
    this.tClass = tClass;
  }

  public Class getRetrofitClass() {
    return tClass;
  }
}
