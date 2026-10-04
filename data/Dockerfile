FROM python:3.12-slim-bookworm

# osmium-tool for filtering the extract; pyosmium (pip "osmium") for splitting ways
RUN apt-get update \
    && apt-get install -y --no-install-recommends osmium-tool curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY routerank_import routerank_import
COPY places-extra.csv .

ENTRYPOINT ["python", "-m", "routerank_import"]
