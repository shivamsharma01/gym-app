using NetSDKCS;
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AccessDemo2s
{
    public partial class FirstEnterForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;
        private int m_Channel = 0;
        private NET_CFG_ACCESS_EVENT_INFO cfg = new NET_CFG_ACCESS_EVENT_INFO();

        private const string CFG_CMD_ACCESS_EVENT = "AccessControl";

        public FirstEnterForm()
        {
            InitializeComponent();
        }

        public FirstEnterForm(IntPtr loginid, int channel)
        {
            InitializeComponent();
            m_LoginID = loginid;
            m_Channel = channel;
        }

        private void FirstEnterForm_Load(object sender, EventArgs e)
        {
            cmb_Door.Items.Clear();
            if (m_Channel > 0)
            {
                for (int i = 1; i <= m_Channel; i++)
                {
                    cmb_Door.Items.Add(i);
                }
                cmb_Door.SelectedIndex = 0;
            }

            if (GetConfig())
            {
                if (cfg.abFirstEnterEnable == 1 && cfg.stuFirstEnterInfo.bEnable)
                {
                    chb_FirstEnter.Checked = true;
                }
                else
                {
                    chb_FirstEnter.Checked = false;
                }
                cmb_Status.SelectedIndex = (int)cfg.stuFirstEnterInfo.emStatus;
                txt_Index.Text = cfg.stuFirstEnterInfo.nTimeIndex.ToString();
            }
        }

        private void btn_Get_Click(object sender, EventArgs e)
        {
            if (GetConfig())
            {
                MessageBox.Show("Get successfully()");

                if (cfg.abFirstEnterEnable == 1 && cfg.stuFirstEnterInfo.bEnable)
                {
                    chb_FirstEnter.Checked = true;
                }
                else
                {
                    chb_FirstEnter.Checked = false;
                }
                cmb_Status.SelectedIndex = (int)cfg.stuFirstEnterInfo.emStatus;
                txt_Index.Text = cfg.stuFirstEnterInfo.nTimeIndex.ToString();
            }
        }

        private void btn_Set_Click(object sender, EventArgs e)
        {
            try
            {
                cfg.abFirstEnterEnable = 1;
                if (chb_FirstEnter.Checked)
                {
                    cfg.stuFirstEnterInfo.bEnable = true;
                }
                else
                {
                    cfg.stuFirstEnterInfo.bEnable = false;
                }
                cfg.stuFirstEnterInfo.emStatus = (EM_CFG_ACCESS_FIRSTENTER_STATUS)cmb_Status.SelectedIndex;
                cfg.stuFirstEnterInfo.nTimeIndex = int.Parse(txt_Index.Text);
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                return;
            }

            if (SetConfig(cfg))
            {
                MessageBox.Show("Set successfully()");
            }
        }

        public bool GetConfig()
        {
            bool bRet = false;
            try
            {
                object objTemp = new object();
                bRet = NETClient.GetNewDevConfig(m_LoginID, cmb_Door.SelectedIndex, CFG_CMD_ACCESS_EVENT, ref objTemp, typeof(NET_CFG_ACCESS_EVENT_INFO), 5000);
                if (bRet)
                {
                    cfg = (NET_CFG_ACCESS_EVENT_INFO)objTemp;
                }
                else
                {
                    MessageBox.Show(NETClient.GetLastError());
                }
            }
            catch (NETClientExcetion nex)
            {
                MessageBox.Show(nex.Message);
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            return bRet;
        }

        public bool SetConfig(NET_CFG_ACCESS_EVENT_INFO cfg)
        {
            bool bRet = false;
            try
            {
                bRet = NETClient.SetNewDevConfig(m_LoginID, cmb_Door.SelectedIndex, CFG_CMD_ACCESS_EVENT, (object)cfg, typeof(NET_CFG_ACCESS_EVENT_INFO), 5000);
            }
            catch (NETClientExcetion nex)
            {
                MessageBox.Show(nex.Message);
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            return bRet;
        }

        
    }
}
