"""Per-language morphology for `tools/build_conjugations.py`.

Each module here describes one language's paradigms and its irregularity-classification
cascade. The generator imports one by `--lang` and installs its symbols as its own globals, so
the pipeline (dump reading, ranking, emission) stays language-agnostic and unduplicated.
"""
