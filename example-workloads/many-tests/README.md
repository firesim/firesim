# Supporting Many Tests On One Base
In same cases, you would like to run many different tests over the same image.
If you were to make N different workloads, one for each test, you'd end up with
N copies of the binary and image, along with the associated build time of
compiling N kernels. In this example, we demonstrate a trick to work around
this issue.

## Basic Strategy
The basic strategy is as follows:
* Create a single base workload that contains everything needed to run every test.
* Create many copies of a test workload, one for each test you want to run:
    * All test json's should have the same workload name (the "name" option).
    * Each test json should be in its own subdirectory (otherwise FireMarshal
      will try to load them all and complain that multiple workloads have the
      same name)
    * Each test workload should have its own workdir (the subdirectory), its
      own 'run' or 'command' option, and optionally its own testing directory. 
* The example runTest.sh script will copy the test workload to the top level and
  test it. Each time, FireMarshal will modify the image for the new 'run' or
  'command' option, but leave everything else untouched.

This trick works because FireMarshal considers any workload with the same name
to be the same workload, regardless of where the json is. Basically,
FireMarshal just thinks you modified the workload json and rebuilds only the
changed parts.

## Running the Example
To use this example, you can run the 'runTest.sh' script:

    $ ./runTest.sh t0/t.json
    $ ./runTest.sh t1/t.json

This script copies the test workload into the top-level directory and runs
'marshal test' on it.

## Caveats
* This trick may become unnecessary in the future (see https://github.com/firesim/FireMarshal/issues/144)
* Earlier versions of FireMarshal used the entire path to the json to identify
  workloads which would not work with this approach. Make sure you are using a
  recent version of FireMarshal.
* The need to copy (not symlink) the test workloads into the top-level
  directory is due in part to
  https://github.com/firesim/FireMarshal/issues/134. Once that is resolved, it
  should be possible to test the workloads directly in their subdirectories.
