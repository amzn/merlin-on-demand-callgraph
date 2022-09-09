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

from fuzzingbook.Grammars import opts, srange
import string
import random

ID_START_CHARACTERS = string.ascii_lowercase
ID_CHARACTERS = string.ascii_letters + string.digits
SCOPE_STACK = [set()]
ID_TYPE_MAP = {}
ID_TYPES = ["FUNC", "OBJ", "CONST"]
CALLS_TO_ADD = set()
is_calls_to_add_initialized = False

def arg_index():
    i = 1
    while True:
        i = i + 1
        yield i

def set_type(identifier, id_type):
    assert id_type in ID_TYPES
    ID_TYPE_MAP[identifier] = id_type

def get_type(identifier):
    return ID_TYPE_MAP[identifier]

def add_scope():
    SCOPE_STACK.append(set())

def remove_scope(scope):
    if len(SCOPE_STACK) == 1:
        SCOPE_STACK[-1].add(scope)
        return
    SCOPE_STACK.pop()
    SCOPE_STACK[-1].add(scope)

def remove_anonymous_scope():
    if len(SCOPE_STACK) == 1:
        return True
    SCOPE_STACK.pop()
    return True

def add_identifier(identifier):
    SCOPE_STACK[-1].add(identifier)
    set_type(identifier, random.choice(ID_TYPES))

def add_function_identifier(identifier):
    SCOPE_STACK[-1].add(identifier)
    set_type(identifier, "FUNC")

def add_const_identifier(identifier):
    SCOPE_STACK[-1].add(identifier)
    set_type(identifier, "CONST")

def remove_identifier(identifier):
    try:
        SCOPE_STACK[-1].remove(identifier)
    except KeyError:
        return

def use_identifier():
    if len(SCOPE_STACK) == 1 and len(SCOPE_STACK[-1]) == 0:
        return "{}"
    return random.choice([id for scope in SCOPE_STACK for id in scope]) # flattens the scope stack into a single list

def get_function_identifier():
    try:
        return random.choice([id for scope in SCOPE_STACK for id in scope if ID_TYPE_MAP[id] == "FUNC"])
    except:
        return False

def get_object_identifier():
    try:
        return random.choice([id for scope in SCOPE_STACK for id in scope if ID_TYPE_MAP[id] == "OBJ"])
    except:
        return False

def get_constant_identifier():
    try:
        return random.choice([id for scope in SCOPE_STACK for id in scope if ID_TYPE_MAP[id] == "CONST"])
    except:
        return False

def detect_empty_identifier(identifier):
    return identifier != ""

def calls_left_to_add():
    global is_calls_to_add_initialized
    if not is_calls_to_add_initialized:
        for identifier in SCOPE_STACK[0]:  # Identifiers in the global scope
            if ID_TYPE_MAP[identifier] == "FUNC":
                CALLS_TO_ADD.add(identifier)
        is_calls_to_add_initialized = True
    if len(CALLS_TO_ADD) == 0:
        return ""

def get_next_call():
    if len(CALLS_TO_ADD) == 0:
        return False
    call = CALLS_TO_ADD.pop()
    SCOPE_STACK[0].remove(call)
    return call

def reset():
    global SCOPE_STACK, CALLS_TO_ADD, is_calls_to_add_initialized, ID_TYPE_MAP
    SCOPE_STACK = [set()]
    CALLS_TO_ADD = set()
    is_calls_to_add_initialized = False
    ID_TYPE_MAP = {}

JS_CLOSURE_GRAMMAR = {
    "<start>":
        [("<Program>", opts(pre=reset, post=lambda prog: len(prog) > 300))], # If a program is too short, try generating again

    "<Program>":
        [("<Inputs><SourceElements><AddedCalls>", opts(order=[1,2,3]))],

    "<Inputs>":
        ["",
        ("<Input>;<Inputs>", opts(prob=0.65))],

    "<Input>":
        ["var <InputIdentifier> = process.argv[<Arg>]"],

    "<InputIdentifier>":
        [("<Identifier>", opts(post=lambda id: add_const_identifier(id)))],

    "<Arg>":
        [("", opts(pre=arg_index))],

    "<SourceElements>":
        ["<SourceElement>",
        ("<SourceElement><SourceElements>", opts(order=[1,2], prob=0.7))],

    "<SourceElement>":
        ["<Statement>",
        ("<FunctionDeclaration>", opts(prob=0.25))],

    "<Statement>":
        ["<VariableDeclaration>",
        "<AssignStatement>",
        "<CallStatement>"],

    "<VariableDeclaration>":
        ["var <VarIdentifier>;"],

    "<VarIdentifier>":
        [("<Identifier>", opts(post=lambda id: add_identifier(id)))],

    "<AssignStatement>":
        [("<FunctionAssign>", opts(prob=0.25)),
        "<CallAssign>"
        "<ObjectAssign>",
        "<ConstantAssign>"],

    "<FunctionAssign>":
        [("<FunctionIdentifier> = <FunctionExpression>;", opts(pre=add_scope, post=lambda id, expr: remove_anonymous_scope() and detect_empty_identifier(id))),
        ("<FunctionIdentifier> = <FunctionIdentifier>;", opts(post=lambda fi1, fi2: detect_empty_identifier(fi1) and detect_empty_identifier(fi2)))],

    "<CallAssign>":
        [("<AnyIdentifier> = <CallExpression>;", opts(post=lambda ai, ce: detect_empty_identifier(ai)))],

    "<ObjectAssign>":
        [("<ObjectIdentifier> = <ObjectExpression>;", opts(post=lambda oi, oe: detect_empty_identifier(oi)))],

    "<ObjectExpression>":
        ["<ObjectLiteral>",
         ("<ObjectIdentifier>", opts(post=lambda oi: detect_empty_identifier(oi)))],

    "<ConstantAssign>":
        [("<ConstantIdentifier> = <Literal>;", opts(post=lambda ci, l: detect_empty_identifier(ci))),
         ("<ConstantIdentifier> = <ConstantIdentifier>;", opts(post=lambda ci1, ci2: detect_empty_identifier(ci1) and detect_empty_identifier(ci2)))],

    "<FunctionExpression>":
        [("function(<FormalParameterList>) {<FunctionBody>}", opts(order=[1,2]))],

    "<CallStatement>":
        ["<CallExpression>;"],

    "<CallExpression>":
        [("<FunctionIdentifier>(<ArgList>)", opts(order=[1,2], post=lambda fi, al: detect_empty_identifier(fi) and fi not in al))],
        # Make sure function identifier is not empty, then make sure the function isn't called while passing itself as an arugment.
        # While passing itself as an argument is technically allowed, we restrict it to simplify our analysis

    "<DeclaredIdentifier>":
        [("<FunctionIdentifier>", opts(post=detect_empty_identifier)),
        ("<ObjectIdentifier>", opts(post=detect_empty_identifier)),
        ("<ConstantIdentifier>", opts(post=detect_empty_identifier))],

    "<FunctionIdentifier>":
        [("", opts(pre=get_function_identifier))],

    "<ObjectIdentifier>":
        [("", opts(pre=get_object_identifier))],

    "<ConstantIdentifier>":
        [("", opts(pre=get_constant_identifier))],

    "<ArgList>":
        ["<DeclaredIdentifier>",
        ("<DeclaredIdentifier>, <ArgList>", opts(order=[1,2]))],

    "<ObjectLiteral>":
        ["{}"],

    "<Literal>":    # For now, just string literals
        ["<String>"],

    "<String>":
        ["\"<IDCharacters>\""],

    "<FunctionDeclaration>":
        [("function <ScopeIdentifier>(<FormalParameterList>) {<FunctionBody>}",
        opts(order=[1,2,3], pre=add_scope, post=lambda s, p, b: remove_scope(s)))],

    "<ScopeIdentifier>":
        [("<Identifier>", opts(post=lambda id: add_function_identifier(id)))],

    "<Identifier>":
        [("<IDStart><IDCharacters>", opts(post=lambda start, rest: len(rest) < 6))],

    "<IDStart>":
        srange(ID_START_CHARACTERS),

    "<IDCharacters>":
        ["<IDCharacter>",
        "<IDCharacter><IDCharacters>"],

    "<IDCharacter>":
        srange(ID_CHARACTERS),

    "<FormalParameterList>":
        [("<Identifier>", opts(post=lambda id: add_identifier(id))),
        ("<FormalParameterList>, <Identifier>", opts(order=[2,1], post=lambda _, id: add_identifier(id)))],

    "<FunctionBody>":
        ["<SourceElements>",
        ("<SourceElements><ReturnStatement>", opts(order=[1,2], prob=0.7))],

    "<ReturnStatement>":
        ["return <AnyIdentifier>;"],

    "<AnyIdentifier>":
        [("<Identifier>", opts(pre=use_identifier))],

    "<AddedCalls>":  # This expansion adds calls to top-level functions at the end of the program to reduce the amount of dead code
        [("<AddedCall><AddedCalls>", opts(pre=calls_left_to_add, post=lambda ac, acs: not ac.startswith("(")))],

    "<AddedCall>":
        ["<AddedIdentifier>(<ArgList>);"],

    "<AddedIdentifier>":
        [("", opts(pre=get_next_call))]
}