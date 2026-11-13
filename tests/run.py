import sys, traceback
sys.path.insert(0, "tests")
import test_merge
ran = passed = failed = 0
failures = []
for name in sorted(dir(test_merge)):
    if not name.startswith("test_"):
        continue
    ran += 1
    try:
        getattr(test_merge, name)()
        passed += 1
        print(f"PASS {name}")
    except Exception:
        failed += 1
        failures.append(name)
        print(f"FAIL {name}")
        traceback.print_exc()
print(f"\nran={ran} passed={passed} failed={failed}")
sys.exit(1 if failed else 0)
