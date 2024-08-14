#!/bin/bash
set -e

rsync -a --delete --exclude .git --exclude .gitignore ../main/repo/ .
git config --global user.email "177773202+Kohi-den-Bot@users.noreply.github.com"
git config --global user.name "Kohi-den-Bot"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -S -m "Update extensions repo"
    git push

    # Purge cached index on jsDelivr
    curl https://purge.jsdelivr.net/gh/aniyomiorg/aniyomi-extensions@repo/index.min.json
else
    echo "No changes to commit"
fi
