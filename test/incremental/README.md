Tests the incremental building capabilities of the system. The test procedure
is fairly specific, so it's best to always use the test.py script to test this. 

Incremental builds means that FireMarshal won't rebuild images from the base
unless it's the first time building it. All subsequent builds will copy over
any files in the overlay or file list and re-run the guest-init.

= How the test works =

1. First Build: The first time you build and run the test, the testFile should
   have some default value in it (e.g. "Global : file").
2. First Launch: The run command will append a "Global : command" to the file
   runOutput every time the image is launched.
3. Modify testFile: We then modify the test file on the host to trigger an
   incremental build (in this case we change it to say "Global : incremental"
   instead of "Global : file")
4. Final build: The system will update testFile, but it won't touch runOutput.
5. Final launch: The test will append "Global : command" to runOutput (there's
   now two such messages because it's launched twice).

Steps 4/5 are done with the "test" command and we've setup the refOutput to
match the expected output of this second run.

