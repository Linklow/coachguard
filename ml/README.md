# Phishing link model: training

This directory trains the model bundled in `coachguard-links`. Training data is not committed;
the steps below download it.

## Files

| File | Purpose |
|---|---|
| `url_features.py` | Feature specification. `HostFeatures.kt` is a port of it. |
| `test_url_features.py` | Checks of the specification; the Kotlin tests assert the same cases. |
| `train_phishing_model.py` | Builds the dataset, trains, evaluates, exports the model and golden predictions. |
| `reports/` | Report and metrics of the last training run. |
| `requirements.txt` | Exact library versions used. |

## Data

Download into `ml/data/`:

| File | Source | License / terms |
|---|---|---|
| `PhiUSIIL_Phishing_URL_Dataset.csv` | [UCI ML Repository, dataset 967](https://archive.ics.uci.edu/dataset/967/phiusiil+phishing+url+dataset) | CC BY 4.0 |
| `public_suffix_list.dat` | [publicsuffix.org](https://publicsuffix.org/list/public_suffix_list.dat) | MPL 2.0 |
| `umbrella/top-1m.csv` | [Cisco Umbrella popularity list](https://s3-us-west-1.amazonaws.com/umbrella-static/top-1m.csv.zip), used only as training data (legitimate subdomain hosts) | Free popularity list; not redistributed |
| `majestic_million.csv` | [Majestic Million](https://downloads.majestic.com/majestic_million.csv), source of the bundled popular-domain list | CC BY 3.0 |
| `openphish-YYYY-MM-DD.txt` | [OpenPhish community feed](https://openphish.com/feed.txt), used only for evaluation | Free for non-commercial use; not redistributed |

PhiUSIIL citation: A. Prasad and S. Chandra, "PhiUSIIL: A diverse security profile empowered
phishing URL detection framework based on similarity index and incremental learning,"
*Computers & Security*, vol. 136, 2024, doi:10.1016/j.cose.2023.103545.

## Run

```bash
py -3.12 -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt
.venv/Scripts/python test_url_features.py
.venv/Scripts/python train_phishing_model.py
```

Then run the Kotlin tests, which check that the device implementation reproduces the Python
scores:

```bash
cd ..
./gradlew :coachguard-links:testDebugUnitTest
```

Training is deterministic for a given set of input files (fixed seeds). The Umbrella list and the
OpenPhish feed change daily, so a later download gives slightly different numbers.
