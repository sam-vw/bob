# Bob

Builder of Builders

An annotation processor that generates builders that track which properties have been set.
This means we can ensure `build` is only called once all arguments have been set.
Missing values is a compile time error, not runtime.

Phantom types are used to track which values have been set. This leads to ludicrously long type
definitions. 

## Usage

Annotate a class with `@Bob`.

```java
@Bob
public class OrderView extends OrderViewBuilders {

    public OrderView(List<Product> products, Address address, Money total) {
        ...
    }
}
```

Then build it with the generated `<Name>Builder`. The `with` methods can be called in any order:

```java
OrderView view = OrderView.from(OrderViewBuilder.builder()
        .withProducts(products)
        .withAddress(address)
        .withTotal(total));
```

Leaving a call out fails to compile:

```
incompatible types: OrderViewBuilder<Missing,Present,Present> cannot be converted to OrderViewBuilder<Present,Present,Present>
```

The annotated class must be a top level, non generic, non abstract class with exactly one non private constructor.

## Why you should use it

* Forgetting an argument is caught by the compiler, not by a `NullPointerException` in production.
* Adding a constructor parameter breaks every call site that doesn't set it, so none are missed.
* Arguments are named at the call site, so two parameters of the same type can't be silently swapped
  as they can with a long constructor call.
* Arguments can be set in any order.
* Everything happens at compile time. There is no reflection, and the only runtime dependency is a
  handful of marker types.

## Why you shouldn't use it

* As mentioned, the ludicrously long type definitions
* The annotated class must extend the generated `<Name>Builders`, so it can't extend anything else.
* Every argument is required. There is no support for optional arguments or default values.
* Only one constructor is supported, and generic classes and generic constructors are rejected.
* It checks that each argument was set, not what it was set to, so `withProducts(null)` still compiles.
* Compile errors are type mismatches between builder types rather than a message naming the missing
  argument, and they get harder to read as the parameter count grows.
* Passing a partially built builder around means writing out its full type, one type parameter per
  constructor argument.
* Every `with` call creates a new builder.

## Adding it to a project

```xml
<dependency>
    <groupId>com.github.samblake.bob</groupId>
    <artifactId>builder</artifactId>
    <version>1.0</version>
</dependency>
<dependency>
    <groupId>com.github.samblake.bob</groupId>
    <artifactId>processor</artifactId>
    <version>1.0</version>
    <scope>provided</scope>
</dependency>
```

If the project sets `annotationProcessorPaths` on `maven-compiler-plugin`, processors on the classpath are ignored,
so add `processor` to that list as well.

In IntelliJ, annotation processing must be enabled for the generated classes to be found.
