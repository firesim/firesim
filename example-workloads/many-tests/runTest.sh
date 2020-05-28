#!/bin/bash
set -e

cp $1 t.json
marshal test t.json
