#!/bin/bash

CONDA_INSTALL_PREFIX=/opt/conda
CONDA_INSTALLER_VERSION=4.12.0-0
CONDA_INSTALLER="https://github.com/conda-forge/miniforge/releases/download/${CONDA_INSTALLER_VERSION}/Miniforge3-${CONDA_INSTALLER_VERSION}-Linux-x86_64.sh"
CONDA_ENV_NAME="base"

DRY_RUN_OPTION=""
DRY_RUN_ECHO=()

usage()
{
    echo "Usage: $0 [options]"
    echo
    echo "Options:"
    echo "[--help]                  List this help"
    echo "[--prefix <prefix>]       Install prefix for conda. Defaults to /opt/conda."
    echo "                          If <prefix>/bin/conda already exists, it will be used."
    echo "[--env <name>]            Name of environment to create for conda. Defaults to installing in base env."
    echo "[--dry-run]               Pass-through to all conda commands and only print other commands."
    echo "                          NOTE: --dry-run will still install conda to --prefix"
    echo
    echo "Examples:"
    echo "  % $0"
    echo "     Install into default system-wide prefix (using sudo if needed) and add install to system-wide /etc/profile.d"
    echo "  % $0 --prefix ~/conda --env my_custom_env"
    echo "     Install into $HOME/conda and add install to ~/.bashrc"
    echo "  % $0 --prefix \${CONDA_EXE%/bin/conda} --env my_custom_env"
    echo "     Create my_custom_env in existing conda install"
    echo "     NOTE: CONDA_EXE is set in your environment when you activate a conda env"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --help)
            usage
            exit 1
            ;;
        --prefix)
            shift
            CONDA_INSTALL_PREFIX="$1"
            shift
            ;;
        --env)
            shift
            CONDA_ENV_NAME="$1"
            shift
            ;;
        --dry-run)
            shift
            DRY_RUN_OPTION="--dry-run"
            DRY_RUN_ECHO=(echo "Would Run:")
            ;;
        *)
            echo "Invalid Argument: $1"
            usage
            exit 1
            ;;
    esac
done

set -ex
set -o pipefail

{

    if [[ ! -r /etc/os-release ]]; then
        echo "::ERROR:: $0 depends on /etc/os-release for distro-specific setup and it doesn't exist here"
        exit 1
    fi

    if [[ "$(uname -mo)" != "x86_64 GNU/Linux" ]]; then
        echo "::ERROR:: $0 only supports 'x86_64 GNU/Linux' not '$(uname -io)'"
        exit 1
    fi

    OS_FLAVOR=$(grep '^ID=' /etc/os-release | awk -F= '{print $2}' | tr -d '"')
    OS_VERSION=$(grep '^VERSION_ID=' /etc/os-release | awk -F= '{print $2}' | tr -d '"')

    echo "machine launch script started" > machine-launchstatus
    chmod ugo+r machine-launchstatus

    # platform-specific setup 
    case "$OS_FLAVOR" in
        ubuntu)
            ;;
        centos)
            ;;
        *)
            echo "::ERROR:: Unknown OS flavor '$OS_FLAVOR'. Unable to do platform-specific setup."
            exit 1
            ;;
    esac


    # everything else is platform-agnostic and could easily be expanded to Windows and/or OSX

    SUDO=""
    prefix_parent=$(dirname "$CONDA_INSTALL_PREFIX")
    if [[ ! -e "$prefix_parent" ]]; then
        mkdir -p "$prefix_parent" || SUDO=sudo
    elif [[ ! -w "$prefix_parent" ]]; then
        SUDO=sudo
    fi

    if [[ -n "$SUDO" ]]; then
        echo "::INFO:: using 'sudo' to install conda"
        # ensure files are read-execute for everyone
        umask 022
    fi

    if [[ -n "$SUDO"  || "$(id -u)" == 0 ]]; then
        INSTALL_TYPE=system
    else
        INSTALL_TYPE=user
    fi

    # to enable use of sudo and avoid modifying 'secure_path' in /etc/sudoers, we specify the full path to conda
    CONDA_EXE="${CONDA_INSTALL_PREFIX}/bin/conda"

    if [[ -x "$CONDA_EXE" ]]; then
        echo "::INFO:: '$CONDA_EXE' already exists, skipping conda install"
    else
        wget -O install_conda.sh "$CONDA_INSTALLER"  || curl -fsSLo install_conda.sh "$CONDA_INSTALLER"
        echo "::INFO:: installing conda to '$CONDA_INSTALL_PREFIX'"
        # -b for non-interactive install
        $SUDO bash ./install_conda.sh -b -p "$CONDA_INSTALL_PREFIX"

        # see https://conda-forge.org/docs/user/tipsandtricks.html#multiple-channels
        # for more information on strict channel_priority
        $SUDO "$CONDA_EXE" config --system --set channel_priority strict
        # By default, don't mess with people's PS1, I personally find it annoying
        $SUDO "$CONDA_EXE" config --system --set changeps1 false

        conda_init_extra_args=()
        if [[ "$INSTALL_TYPE" == system ]]; then
            # if we're installing into a root-owned directory using sudo, or we're already root
            # initialize conda in the system-wide rcfiles
            conda_init_extra_args=(--no-user --system)
        fi
        $SUDO "${CONDA_EXE}" init $DRY_RUN_OPTION "${conda_init_extra_args[@]}" bash
    fi

    # https://conda-forge.org/feedstock-outputs/ 
    #   filterable list of all conda-forge packages
    # https://conda-forge.org/#contribute 
    #   instructions on adding a recipe
    # https://docs.conda.io/projects/conda/en/latest/user-guide/concepts/pkg-specs.html#package-match-specifications
    #   documentation on package_spec syntax
    CONDA_PACKAGE_SPECS=()


    # https://docs.conda.io/projects/conda-build/en/latest/resources/compiler-tools.html#using-the-compiler-packages
    #    for notes on using the conda compilers
    #CONDA_PACKAGE_SPECS+=( gcc_linux-64 gxx_linux-64 )


    # poky deps
    CONDA_PACKAGE_SPECS+=( python=3.8 patch texinfo subversion chrpath git wget )
    # qemu deps
    CONDA_PACKAGE_SPECS+=( gtk3 glib pkg-config bison flex )
    # qemu also requires being built against kernel-headers >= 2.6.38 because of it's use of
    # MADV_NOHUGEPAGE in a call to madvise.  See:
    # https://man7.org/linux/man-pages/man2/madvise.2.html
    # https://conda-forge.org/docs/maintainer/knowledge_base.html#using-centos-7
    # obvi this would need to be made linux-specific if we supported other MacOS or Windows
    CONDA_PACKAGE_SPECS+=( "kernel-headers_linux-64>=2.6.38" )
    # firemarshal deps
    CONDA_PACKAGE_SPECS+=( rsync psutil doit gitpython humanfriendly e2fsprogs ctags bison flex expat )
    # cross-compile glibc 2.28+ deps
    CONDA_PACKAGE_SPECS+=( make )
    # build-libelf wants autoconf
    CONDA_PACKAGE_SPECS+=( autoconf automake libtool )
    # other misc deps
    CONDA_PACKAGE_SPECS+=(
        sbt \
        ca-certificates \
        mosh \
        gmp \
        mpfr \
        mpc \
        zlib \
        vim \
        git  \
        openjdk \
        gengetopt \
        libffi \
        expat \
        libusb1 \
        ncurses \
        cmake \
        graphviz \
        expect \
        dtc \
        verilator==4.034 \
    )

    # python packages
    # While it is possible to install using pip after creating the
    # conda environment, pip's dependency resolution can conflict with
    # conda and create broken environments.  It's best to use the conda
    # packages so that the environment is consistent
    CONDA_PACKAGE_SPECS+=( \
        boto3==1.20.21 \
        colorama==0.4.3 \
        argcomplete==1.12.3 \
        python-graphviz==0.19 \
        pyparsing==3.0.6 \
        numpy==1.19.5 \
        kiwisolver==1.3.1 \
        matplotlib==3.3.4 \
        pandas==1.1.5 \
        awscli==1.22.21 \
        pytest==6.2.5 \
        pytest-dependency==0.5.1 \
        pytest-mock==3.7.0 \
        moto==3.1.0 \
        pyyaml==5.4.1 \
        mypy==0.931 \
        types-pyyaml==6.0.4 \
        boto3-stubs==1.21.6 \
        botocore-stubs==1.24.7 \
        mypy-boto3-s3==1.21.0 \
    )

    if [[ "$CONDA_ENV_NAME" == "base" ]]; then
        # see also: https://github.com/firesim/firesim/pull/986#discussion_r830207675
        # we default to installing into the base environment against best practice because it
        # simplifies adding our environment as the default to the system-wide /etc/profile.d/conda.sh
        CONDA_SUBCOMMAND=install
        CONDA_ENV_BIN="${CONDA_INSTALL_PREFIX}/bin"
    else
        CONDA_ENV_BIN="${CONDA_INSTALL_PREFIX}/envs/${CONDA_ENV_NAME}/bin"
        if [[ -d "${CONDA_INSTALL_PREFIX}/envs/${CONDA_ENV_NAME}" ]]; then
            # 'create' clobbers the existing environment and doesn't leave a revision entry in
            # `conda list --revivsions`, so use install instead
            CONDA_SUBCOMMAND=install
        else
            CONDA_SUBCOMMAND=create
        fi
    fi

    # to enable use of sudo and avoid modifying 'secure_path' in /etc/sudoers, we specify the full path to pip
    CONDA_PIP_EXE="${CONDA_ENV_BIN}/pip"

    # to enable use of sudo and avoid modifying 'secure_path' in /etc/sudoers, we specify the full path to conda
    $SUDO "${CONDA_EXE}" "$CONDA_SUBCOMMAND" $DRY_RUN_OPTION -n "$CONDA_ENV_NAME" -y "${CONDA_PACKAGE_SPECS[@]}"


    # Install python packages using pip that are not available from conda
    #
    # Installing things with pip is possible.  However, to get
    # the most complete solution to all dependencies, you should
    # prefer creating the environment with a single invocation of
    # conda
    PIP_PKGS=( \
        fab-classic==1.19.1 \
        mypy-boto3-ec2==1.21.9 \
        sure==2.0.0 \
    )
    if [[ -n "$PIP_PKGS[*]" ]]; then
        "${DRY_RUN_ECHO[@]}" $SUDO "${CONDA_PIP_EXE}" install "${PIP_PKGS[@]}"
    fi


    argcomplete_extra_args=()
    if [[ "$INSTALL_TYPE" != system ]]; then
        # if we're aren't installing into a system directory, then initialize argcomplete
        # with --user so that it goes into the home directory
        argcomplete_extra_args=( --user )
    fi
    "${DRY_RUN_ECHO[@]}" $SUDO "${CONDA_ENV_BIN}/activate-global-python-argcomplete" "${argcomplete_extra_args[@]}"

} 2>&1 | tee machine-launchstatus.log
chmod ugo+r machine-launchstatus.log


echo "machine launch script completed" >>machine-launchstatus
