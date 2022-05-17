Building Docs
--------------

    sudo -E $CONDA_EXE install --file ./requirements.txt
    make html

Look in the `_build/html` directory for output. You can also run

    python3 -m http.server

To get a proper locally-hosted version.

If you want to generate a pdf version, you will additionally need:

    wget http://mirror.ctan.org/systems/texlive/tlnet/install-tl-unx.tar.gz
    tar -xvf install-tl-unx.tar.gz
    cd install-tl*
    sudo /bin/bash -l -c ./install-tl

Follow the prompts, add the suggested path to your `.bashrc`.
