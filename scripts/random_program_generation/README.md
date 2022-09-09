# Description
The scripts can be used to generate random syntactically valid ES5 JavaScript programs that use closures

# Dependencies
These scripts use code from Andreas Zeller's [Fuzzing Book](https://www.fuzzingbook.org/). The dependency can be installed with pip:
```
pip install fuzzingbook
```
# Usage
Run using
```
python3 fuzz.py
```

By default, generated programs are written to `<project_directory>/datasets/fuzzed_closure`