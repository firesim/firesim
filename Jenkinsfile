pipeline {
    agent { label 'firesim-worker' }
    stages {
        stage('Checkout') {
            steps {
                cleanWs()
                git 'https://github.com/firesim/firesim'   
            }
        }
        stage('Single-Node_Build') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'FIRESIM_PEM', \
                                             keyFileVariable: 'PEM_KEY', \
                                             passphraseVariable: '', \
                                             usernameVariable: ''),
                                [$class: 'AmazonWebServicesCredentialsBinding',
                                    credentialsId: 'aws-key',
                                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                                    ]]) {
                    withEnv(['PATH+EXTRA=/home/centos/bin:/home/centos/.local/bin']) {
                        sh '''#!/bin/bash
                            set -x
                            # Make sure firesim pem file exists
                            cp $PEM_KEY ~/firesim.pem
                            chmod u+wr ~/firesim.pem
                            
                            # Configure AWS 
                            aws configure set aws_access_key_id ${AWS_ACCESS_KEY_ID}
                            aws configure set aws_secret_access_key ${AWS_SECRET_ACCESS_KEY}
                            aws configure set default.region us-east-1
                            aws configure set default.output json  
                            
                            RDIR=$(pwd)
                            chmod u+x $RDIR/scripts/machine-launch-script.sh
                            bash -x scripts/machine-launch-script.sh
                            
                            bash -x build-setup.sh fast
                            source sourceme-f1-manager.sh 
                            
                            for CONFIG_FILE in build build_recipes hwdb runtime
                            do
                                cp ${RDIR}/deploy/sample-backup-configs/sample_config_${CONFIG_FILE}.ini ${RDIR}/deploy/config_${CONFIG_FILE}.ini
                            done   
                            cd $RDIR/sw/firesim-software
                            bash -x build.sh
                            cd $RDIR
                            
                            # Need to escape the escape characters (in Jenkins->sh)
                            sed -i -e 's/^\\(f1_16xlarges\\).*/\\1=0/' \
                                    -e 's/^\\(f1_2xlarges\\).*/\\1=1/' \
                                    -e 's/^\\(topology\\).*/\\1=no_net_config/' \
                                    -e 's/^\\(no_net_num_nodes\\).*/\\1=1/' \
                                    -e 's/^\\(linklatency\\).*/\\1=6405/' \
                                    -e 's/^\\(switchinglatency\\).*/\\1=10/' \
                                    -e 's/^\\(defaulthwconfig\\).*/\\1=firesim-quadcore-no-nic-ddr3-llc4mb/' \
                                    -e 's/^\\(netbandwidth\\).*/\\1=200/' ${RDIR}/deploy/config_runtime.ini
                            cat ${RDIR}/deploy/config_runtime.ini
                            firesim launchrunfarm 
                            nohup firesim infrasetup
                            
                            timeout 200s firesim runworkload
                            firesim terminaterunfarm -q
                        '''
                    }
                }
            }
        }
        stage('Eight-Node_Build') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'FIRESIM_PEM', \
                                             keyFileVariable: 'PEM_KEY', \
                                             passphraseVariable: '', \
                                             usernameVariable: ''),
                                [$class: 'AmazonWebServicesCredentialsBinding',
                                    credentialsId: 'aws-key',
                                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                                    ]]) {
                    withEnv(['PATH+EXTRA=/home/centos/bin:/home/centos/.local/bin']) {
                        sh '''#!/bin/bash
                            set -x
                            # Make sure firesim pem file exists
                            cp $PEM_KEY ~/firesim.pem
                            chmod u+wr ~/firesim.pem
                            
                            # Configure AWS
                            aws configure set aws_access_key_id ${AWS_ACCESS_KEY_ID}
                            aws configure set aws_secret_access_key ${AWS_SECRET_ACCESS_KEY}
                            aws configure set default.region us-east-1
                            aws configure set default.output json  
                            
                            RDIR=$(pwd)
                            chmod u+x $RDIR/scripts/machine-launch-script.sh
                            bash -x scripts/machine-launch-script.sh
                            
                            bash -x build-setup.sh fast
                            source sourceme-f1-manager.sh 
                            
                            for CONFIG_FILE in build build_recipes hwdb runtime
                            do
                                cp ${RDIR}/deploy/sample-backup-configs/sample_config_${CONFIG_FILE}.ini ${RDIR}/deploy/config_${CONFIG_FILE}.ini
                            done
                            cd $RDIR/sw/firesim-software
                            bash -x build.sh
                            cd $RDIR
                            
                            # Need to escape the escape characters (in Jenkins->sh)
                            sed -i -e 's/^\\(f1_16xlarges\\).*/\\1=1/' \
                                    -e 's/^\\(f1_2xlarges\\).*/\\1=0/' \
                                    -e 's/^\\(topology\\).*/\\1=example_8config/' \
                                    -e 's/^\\(no_net_num_nodes\\).*/\\1=2/' \
                                    -e 's/^\\(linklatency\\).*/\\1=6405/' \
                                    -e 's/^\\(switchinglatency\\).*/\\1=10/' \
                                    -e 's/^\\(netbandwidth\\).*/\\1=200/' \
                                    -e 's/^\\(defaulthwconfig\\).*/\\1=firesim-quadcore-nic-ddr3-llc4mb/' ${RDIR}/deploy/config_runtime.ini 
                            cat ${RDIR}/deploy/config_runtime.ini
                            firesim launchrunfarm 
                            nohup firesim infrasetup
                            
                            timeout 200s firesim runworkload
                            firesim terminaterunfarm -q
                        '''
                    }
                }
            }
        }
        stage('FPGA-Image-Build') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'FIRESIM_PEM', \
                                             keyFileVariable: 'PEM_KEY', \
                                             passphraseVariable: '', \
                                             usernameVariable: ''),
                                [$class: 'AmazonWebServicesCredentialsBinding',
                                    credentialsId: 'aws-key',
                                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                                    ]]) {
                    withEnv(['PATH+EXTRA=/home/centos/bin:/home/centos/.local/bin']) {
                        sh '''#!/bin/bash
                            set -x
                            
                            # Make sure firesim pem file exists
                            cp $PEM_KEY ~/firesim.pem
                            chmod u+wr ~/firesim.pem
                            
                            # Configure AWS
                            aws configure set aws_access_key_id ${AWS_ACCESS_KEY_ID}
                            aws configure set aws_secret_access_key ${AWS_SECRET_ACCESS_KEY}
                            aws configure set default.region us-east-1
                            aws configure set default.output json  
                            
                            RDIR=$(pwd)
                            chmod u+x $RDIR/scripts/machine-launch-script.sh
                            bash -x scripts/machine-launch-script.sh
                            
                            bash -x build-setup.sh fast
                            source sourceme-f1-manager.sh 
                            
                            for CONFIG_FILE in build build_recipes hwdb runtime
                            do
                                cp ${RDIR}/deploy/sample-backup-configs/sample_config_${CONFIG_FILE}.ini ${RDIR}/deploy/config_${CONFIG_FILE}.ini
                            done
                            
                            cd $RDIR/sw/firesim-software
                            bash -x build.sh
                            cd $RDIR
                            
                            ls ${RDIR}/deploy
                            sed -i -e 's/^\\(s3bucketname\\).*/\\1=jenkinsfiresim/' \
                                    -e 's/^\\(firesim-singlecore-nic-lbp\\).*/#\\1/' \
                                    -e 's/^\\(firesim-quadcore-nic-lbp\\).*/#\\1/' \
                                    -e 's/^\\(firesim-quadcore-no-nic-lbp\\).*/#\\1/' \
                                    -e 's/^\\(firesim-quadcore-nic-ddr3-llc4mb\\).*/#\\1/' \
                                    -e 's/^\\(firesim-quadcore-no-nic-ddr3-llc4mb\\).*/#\\1/' ${RDIR}/deploy/config_build.ini
                            cat ${RDIR}/deploy/config_build.ini   
                            
                            firesim buildafi
                        '''
                    }
                }
            }
        }                       
    }
}
