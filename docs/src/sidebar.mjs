const onPremises = 'getting-started-guides/on-premises-fpga-getting-started';
const awsTutorial = 'getting-started-guides/aws-ec2-f2-getting-started';

export const sidebar = [
  {
    label: 'Getting Started',
    items: ['firesim-basics', 'terminology'],
  },
  {
    label: 'Initial Setup',
    items: [
      {
        label: 'AWS EC2 F2',
        items: [{ autogenerate: { directory: 'AWS-EC2-F2-Initial-Setup' } }],
      },
      'local-fpga-initial-setup',
    ],
  },
  {
    label: 'Chipyard-based Starter Tutorials',
    items: [
      {
        label: 'AWS EC2 F2',
        items: [
          awsTutorial,
          `${awsTutorial}/setting-up-the-firesim-repo`,
          {
            label: 'Running Simulations',
            items: [{ autogenerate: { directory: 'Getting-Started-Guides/AWS-EC2-F2-Getting-Started/Running-Simulations' } }],
          },
          `${awsTutorial}/building-a-firesim-afi`,
        ],
      },
      ...[
        ['Xilinx Alveo U200', 'xilinx-alveo-u200'],
        ['Xilinx Alveo U250', 'xilinx-alveo-u250'],
        ['Xilinx Alveo U280', 'xilinx-alveo-u280'],
        ['Xilinx VCU118', 'xilinx-vcu118'],
        ['RHS Research Nitefury II', 'rhs-research-nitefury-ii'],
      ].map(([label, board]) => ({
        label,
        items: [
          { slug: `${onPremises}/${board}-fpgas`, attrs: { 'data-sphinx-index': 'true' } },
          `${onPremises}/initial-setup/${board}`,
          `${onPremises}/repo-setup/${board}`,
          `${onPremises}/running-simulations/running-single-node-simulation-${board}`,
          `${onPremises}/building-a-firesim-bitstream/${board}`,
        ],
      })),
      {
        label: 'Xilinx Vitis',
        items: [
          { slug: `${onPremises}/xilinx-vitis-fpgas`, attrs: { 'data-sphinx-index': 'true' } },
          `${onPremises}/initial-setup/xilinx-vitis-fpgas`,
          `${onPremises}/running-simulations/running-single-node-simulation-xilinx-vitis`,
          `${onPremises}/building-a-firesim-bitstream/xilinx-vitis`,
        ],
      },
    ],
  },
  {
    label: 'Advanced Docs',
    items: [
      { label: 'Manager', items: [{ autogenerate: { directory: 'Advanced-Usage/Manager' } }] },
      { label: 'Workloads', items: [{ autogenerate: { directory: 'Advanced-Usage/Workloads' } }] },
      'advanced-usage/generating-different-targets',
      {
        label: 'Debugging in Software',
        items: [{ autogenerate: { directory: 'Advanced-Usage/Debugging-in-Software' } }],
      },
      {
        label: 'Debugging and Profiling on FPGA',
        items: [{ autogenerate: { directory: 'Advanced-Usage/Debugging-and-Profiling-on-FPGA' } }],
      },
      'advanced-usage/conda',
      'advanced-usage/supernode',
      {
        label: 'FireAxe: Partitioning onto Multiple FPGAs',
        items: [{ autogenerate: { directory: 'Advanced-Usage/FireAxe-Partitioning-onto-Multiple-FPGAs' } }],
      },
      'advanced-usage/miscellaneous-tips',
      'advanced-usage/adding-fpgas',
      'advanced-usage/firesim-without-chipyard',
      'advanced-usage/faqs',
    ],
  },
  {
    label: 'Compiler (Golden Gate) Docs',
    items: [
      'golden-gate/overview',
      'golden-gate/li-bdn',
      'golden-gate/bridges',
      'golden-gate/bridge-walkthrough',
      'golden-gate/triggers',
      'golden-gate/resource-optimizations',
      'golden-gate/output-files',
    ],
  },
  {
    label: 'Developer Docs',
    items: [
      'developer-docs/goldengate-and-driver-development',
      'developer-docs/host-platform-debugging',
      'developer-docs/vscode-integration',
      'developer-docs/managing-conda-lock-file',
      'developer-docs/manager-development',
    ],
  },
  { label: 'Miscellaneous', items: ['external-tutorial-setup'] },
];
