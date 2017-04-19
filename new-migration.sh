#!/bin/sh

if [ -z "$1" ]; then
    echo "Please supply a reason for your migration.\n
          Usage: new-migration <reason>"
else
    migrations_path="resources/migrations"
    format="%Y-%m-%d-%H-%M-%S"
    migration_date=$(date -u +"$format")
    filename_date=$(date -jf "$format" $migration_date "+%Y%m%d%H%M%S")
    filename=$(printf "%s_%s.edn" "$filename_date" "$1")
    tx_date=$(printf "%s-UTC" "$migration_date")
    echo "{:$tx_date\n {:txes []}}" > "$migrations_path/$filename"
fi

