# Synthetic landmarks

The two checked-in replay fixtures are generated with `uv run python -m scripts.gen_landmarks`. Both contain ten squats; `squat_shallow_10` has six injected shallow movements, and `squat_good_10` has ten deeper movements. These are artificial geometry, not captured people or device evidence. The evaluation generator creates 200 varied, seeded sets under ignored `var/landmarks` to avoid committing hundreds of megabytes of derived data. Only fixture mode with CLEANREP_ALLOW_REPLAY=1 serves these files.
