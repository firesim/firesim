#!/bin/bash

#Pushes a results-build subdirectory to an S3 bucket

result_dir=$1
dest_dir=$(basename ${result_dir})
bucket_name=iccad2019-midas2

aws s3 cp $result_dir s3://${bucket_name}/${dest_dir} --acl authenticated-read --recursive
exit 0
