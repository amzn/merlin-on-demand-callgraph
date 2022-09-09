# Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# A copy of the License is located at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# or in the "license" file accompanying this file. This file is distributed
# on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing
# permissions and limitations under the License.

import random
import sys
from pathlib import Path
from fuzzingbook.GeneratorGrammarFuzzer import ProbabilisticGeneratorGrammarFuzzer as Fuzzer
from fuzzingbook.ExpectError import ExpectTimeout
from JSGrammars import JS_CLOSURE_GRAMMAR

sys.setrecursionlimit(10**6)  # To let us generate large programs

WRITE_DIR = Path("../../datasets/tentative")


def prettify(generated_program):
    pretty_program = ""
    indent_level = 0
    for ch in generated_program:
        if ch == '}':
            pretty_program += "\n"
            indent_level -= 1
            for _ in range(indent_level):
                pretty_program += "  "
        pretty_program += ch
        if ch == ';':
            pretty_program += "\n"
            for _ in range(indent_level):
                pretty_program += "  "
        elif ch == '{':
            pretty_program += "\n"
            indent_level += 1
            for _ in range(indent_level):
                pretty_program += "  "
        elif ch == '}':
            pretty_program += "\n"
            for _ in range(indent_level):
                pretty_program += "  "
    return pretty_program


fuzzer = Fuzzer(JS_CLOSURE_GRAMMAR, min_nonterminals=2, max_nonterminals=20, replacement_attempts=10)

successfully_generated = 0
while successfully_generated < 50:
    random.random()
    print("Generating program " + str(successfully_generated))
    with ExpectTimeout(60, mute=True):
        program = fuzzer.fuzz()
        print("Finished generating program " + str(successfully_generated))
        pretty_program = prettify(program)
        filename = 'gen' + str(successfully_generated) + '.js'
        target = WRITE_DIR / filename
        with target.open("w") as f:
            f.write(pretty_program)
        successfully_generated += 1
