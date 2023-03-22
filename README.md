## Merlin: On-Demand Call Graph Generation for JavaScript

_Note_: This is work in progress and not suitable for production use.

This repository contains an experimental on-demand call graph construction
algorithm for JavaScript programs, based on [Synchronized Push-Down
Systems](https://johspaeth.github.io/project/spds/). Only a small subset of
ES5 language features are supported at the moment.

## Building and Running

### Installing dependencies
First, check out the submodules by running `git submodule update --init --recursive`.

Then you will need `npm` installed. We recommend using [Node Version Manager (nvm)](https://github.com/nvm-sh/nvm) to install node and npm. Once installed, run:
`cd tajs_vr/extras/babel`
`npm install`

This project is built via [sbt](https://www.scala-sbt.org). Please follow the official [instructions](https://www.scala-sbt.org/1.x/docs/Setup.html) for installing sbt.

This project requires Java 16 or higher. `sbt` can be configured to use Java 16
for this project by creating an `.sbtopts` file containing the following:

```
-java-home
<path-to-your-java16-installation>
```

For example, if you are using using the Amazon Coretto SDK on Mac OSX, the following can be used:

```
-java-home
/Library/Java/JavaVirtualMachines/amazon-corretto-16.jdk/Contents/Home
```

### Running Merlin

After that, the project can be built by running `sbt compile` and run via `sbt run`. From the command line, you can execute:
```
sbt "run --file path/to/js/file --output path/to/output/file"
```

The test suite can be run using `sbt test`.

### Missing Features

Several JavaScript features are not implemented/analyzed by this prototype:

- Loops
- Prototype chains
- Asynchronous code (`async/await`, `Promise`)
- Built-in JavaScript functions

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This project is licensed under the Apache-2.0 License.

## Attribution

This project is built on top of the following static analysis projects:

- [TAJS](https://github.com/cs-au-dk/TAJS); released under Apache-2.0
- [TajsVR](https://github.com/cs-au-dk/tajs_vr/tree/280a5cdb7b3754b4404105f7b989b3844cdea700); released under Apache-2.0
- [SPDS](https://github.com/CodeShield-Security/SPDS); released under EPL-2.0

---

Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
