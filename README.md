# Calderwood: An Event Sourcing/CQRS application template

For the project report see report.pdf

## Requirements

- Linux or OSX (tested on Ubuntu 16.10 and OSX 10.11.6)
- Oracle Java 8
- Leiningen (v2.0 or later)
- Datomic Free v0.9.5544 or later

## Run

Ensure Datomic Free is running

```
cd path-to-datomic-free
bin/transactor free-transactor-template.properties
```

Build the console:

```
lein cljsbuild once
```

Start a REPL:

```
lein repl
```

When in the REPL:

```
(go)
```

Open your browser, and navigate to 
[http://localhost:8080/console](http://localhost:8080/console) .

To run a performance test from the REPL after the system has started:

```
(perf-test)
```
