sudo pip install -r requirements.txt

make html

look in the _build/html directory for output


if you want to generate a pdf version, you will additionally need:

wget http://mirror.ctan.org/systems/texlive/tlnet/install-tl-unx.tar.gz
tar -xvf install-tl-unx.tar.gz
sudo ./install-tl

follow the prompts, add the suggested path to your bashrc
