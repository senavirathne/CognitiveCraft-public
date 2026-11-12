"""Preserve each one-shot metadata probe when instruments are compiled again."""
from pathlib import Path

def fresh_probe(base):
    base=Path(base);base.parent.mkdir(parents=True,exist_ok=True)
    for number in range(100):
        target=base if number==0 else base.with_name(base.name+f'-{number:03d}')
        try:target.mkdir()
        except FileExistsError:continue
        return target
    raise RuntimeError('Instrumentation preparation probe bound reached; retain every prior invocation')
