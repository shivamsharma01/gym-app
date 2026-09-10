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
    public partial class MultidoorInterlockForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;
        private NET_CFG_ACCESS_GENERAL_INFO cfg = new NET_CFG_ACCESS_GENERAL_INFO();

        private const string CFG_CMD_ACCESS_GENERAL = "AccessControlGeneral";

        public MultidoorInterlockForm()
        {
            InitializeComponent();
        }

        public MultidoorInterlockForm(IntPtr loginid)
        {
            InitializeComponent();
            m_LoginID = loginid;
        }

        private void btn_Get_Click(object sender, EventArgs e)
        {
            UIClear();
            if (GetConfig())
            {
                MessageBox.Show("Get successfully()");

                chb_FirstEnter.Checked = cfg.stuABLockInfo.bEnable;
                cmb_Door.SelectedIndex = 0;
            }
        }

        private void btn_Set_Click(object sender, EventArgs e)
        {
            try
            {
                if (chb_FirstEnter.Checked)
                {
                    cfg.abABLockInfo = 1;
                }
                else
                {
                    cfg.abABLockInfo = 0;
                }
                cfg.stuABLockInfo.bEnable = chb_FirstEnter.Checked;

                int num = cmb_Door.SelectedIndex;
                int temp;
                if (!string.IsNullOrEmpty(txt_Door1.Text.Trim()) && int.TryParse(txt_Door1.Text.Trim(), out temp))
                {
                    cfg.stuABLockInfo.stuDoors[num].nDoor = 1;
                    cfg.stuABLockInfo.stuDoors[num].anDoor[0] = temp;
                }
                if (!string.IsNullOrEmpty(txt_Door2.Text.Trim()) && int.TryParse(txt_Door2.Text.Trim(), out temp))
                {
                    cfg.stuABLockInfo.stuDoors[num].nDoor = 2;
                    cfg.stuABLockInfo.stuDoors[num].anDoor[1] = temp;
                }
                if (!string.IsNullOrEmpty(txt_Door3.Text.Trim()) && int.TryParse(txt_Door3.Text.Trim(), out temp))
                {
                    cfg.stuABLockInfo.stuDoors[num].nDoor = 3;
                    cfg.stuABLockInfo.stuDoors[num].anDoor[2] = temp;
                }
                if (!string.IsNullOrEmpty(txt_Door4.Text.Trim()) && int.TryParse(txt_Door4.Text.Trim(), out temp))
                {
                    cfg.stuABLockInfo.stuDoors[num].nDoor = 4;
                    cfg.stuABLockInfo.stuDoors[num].anDoor[3] = temp;
                }

                if (!string.IsNullOrEmpty(txt_Door5.Text.Trim()) && int.TryParse(txt_Door5.Text.Trim(), out temp))
                {
                    cfg.stuABLockInfo.stuDoors[num].nDoor = 5;
                    cfg.stuABLockInfo.stuDoors[num].anDoor[4] = temp;
                }
                if (!string.IsNullOrEmpty(txt_Door6.Text.Trim()) && int.TryParse(txt_Door6.Text.Trim(), out temp))
                {
                    cfg.stuABLockInfo.stuDoors[num].nDoor = 6;
                    cfg.stuABLockInfo.stuDoors[num].anDoor[5] = temp;
                }
                if (!string.IsNullOrEmpty(txt_Door7.Text.Trim()) && int.TryParse(txt_Door7.Text.Trim(), out temp))
                {
                    cfg.stuABLockInfo.stuDoors[num].nDoor = 7;
                    cfg.stuABLockInfo.stuDoors[num].anDoor[6] = temp;
                }
                if (!string.IsNullOrEmpty(txt_Door8.Text.Trim()) && int.TryParse(txt_Door8.Text.Trim(), out temp))
                {
                    cfg.stuABLockInfo.stuDoors[num].nDoor = 8;
                    cfg.stuABLockInfo.stuDoors[num].anDoor[7] = temp;
                }

                cfg.stuABLockInfo.nDoors = 0;
                for (int i = 0; i < 8; i++)
                {
                    int ndoor = cfg.stuABLockInfo.stuDoors[i].nDoor;
                    if (ndoor == 0)
                    {
                        cfg.stuABLockInfo.nDoors = i;
                        break;
                    }
                    else if (i == 7)
                    {
                        cfg.stuABLockInfo.nDoors = 8;
                    }
                }
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
                bRet = NETClient.GetNewDevConfig(m_LoginID, -1, CFG_CMD_ACCESS_GENERAL, ref objTemp, typeof(NET_CFG_ACCESS_GENERAL_INFO), 5000);
                if (bRet)
                {
                    cfg = (NET_CFG_ACCESS_GENERAL_INFO)objTemp;
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

        public bool SetConfig(NET_CFG_ACCESS_GENERAL_INFO cfg)
        {
            bool bRet = false;
            try
            {
                bRet = NETClient.SetNewDevConfig(m_LoginID, -1, CFG_CMD_ACCESS_GENERAL, (object)cfg, typeof(NET_CFG_ACCESS_GENERAL_INFO), 5000);
                if (!bRet)
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

        private void cmb_Door_SelectedIndexChanged(object sender, EventArgs e)
        {
            txt_Door1.Text = "";
            txt_Door2.Text = "";
            txt_Door3.Text = "";
            txt_Door4.Text = "";
            txt_Door5.Text = "";
            txt_Door6.Text = "";
            txt_Door7.Text = "";
            txt_Door8.Text = "";

            int num = cmb_Door.SelectedIndex;
            if (num < 0)
            {
                return;
            }
            if (num > cfg.stuABLockInfo.nDoors || cfg.stuABLockInfo.nDoors < 1)
            {
                return;
            }

            if (cfg.stuABLockInfo.stuDoors[num].nDoor >= 1)
            {
                txt_Door1.Text = cfg.stuABLockInfo.stuDoors[num].anDoor[0].ToString();
            }
            if (cfg.stuABLockInfo.stuDoors[num].nDoor >= 2)
            {
                txt_Door2.Text = cfg.stuABLockInfo.stuDoors[num].anDoor[1].ToString();
            }
            if (cfg.stuABLockInfo.stuDoors[num].nDoor >= 3)
            {
                txt_Door3.Text = cfg.stuABLockInfo.stuDoors[num].anDoor[2].ToString();
            }
            if (cfg.stuABLockInfo.stuDoors[num].nDoor >= 4)
            {
                txt_Door4.Text = cfg.stuABLockInfo.stuDoors[num].anDoor[3].ToString();
            }
            if (cfg.stuABLockInfo.stuDoors[num].nDoor >= 5)
            {
                txt_Door5.Text = cfg.stuABLockInfo.stuDoors[num].anDoor[4].ToString();
            }
            if (cfg.stuABLockInfo.stuDoors[num].nDoor >= 6)
            {
                txt_Door6.Text = cfg.stuABLockInfo.stuDoors[num].anDoor[5].ToString();
            }
            if (cfg.stuABLockInfo.stuDoors[num].nDoor >= 7)
            {
                txt_Door7.Text = cfg.stuABLockInfo.stuDoors[num].anDoor[6].ToString();
            }
            if (cfg.stuABLockInfo.stuDoors[num].nDoor >= 8)
            {
                txt_Door8.Text = cfg.stuABLockInfo.stuDoors[num].anDoor[7].ToString();
            }

        }

        public void UIClear()
        {
            chb_FirstEnter.Checked = false;
            cmb_Door.SelectedIndex = -1;
            txt_Door1.Text = "";
            txt_Door2.Text = "";
            txt_Door3.Text = "";
            txt_Door4.Text = "";
            txt_Door5.Text = "";
            txt_Door6.Text = "";
            txt_Door7.Text = "";
            txt_Door8.Text = "";
        }

        private void MultidoorInterlockForm_Load(object sender, EventArgs e)
        {
            if (GetConfig())
            {
                chb_FirstEnter.Checked = cfg.stuABLockInfo.bEnable;
                cmb_Door.SelectedIndex = 0;
            }
        }
    }
}
