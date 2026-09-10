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
    public partial class OpenDoorGroupForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;
        private int m_Channel = 0;
        private NET_CFG_OPEN_DOOR_GROUP_INFO cfg_info = new NET_CFG_OPEN_DOOR_GROUP_INFO();

        private const string CFG_CMD_OPEN_DOOR_GROUP = "OpenDoorGroup";
        public OpenDoorGroupForm()
        {
            InitializeComponent();
        }

        public OpenDoorGroupForm(IntPtr loginid, int channel)
        {
            InitializeComponent();
            m_LoginID = loginid;
            m_Channel = channel;
        }

        private void OpenDoorGroupForm_Load(object sender, EventArgs e)
        {
            cmb_Channel.Items.Clear();
            if (m_Channel > 0)
            {
                for (int i = 1; i <= m_Channel; i++)
                {
                    cmb_Channel.Items.Add(i);
                }
                cmb_Channel.SelectedIndex = 0;
            }

            UIClear();
            if (GetConfig())
            {
                if (cfg_info.nGroup >= 1)
                {
                    txt_Num1.Text = cfg_info.stuGroupInfo[0].nUserCount.ToString();
                    cmb_UserNum1.SelectedIndex = 0;
                }
                if (cfg_info.nGroup >= 2)
                {
                    txt_Num2.Text = cfg_info.stuGroupInfo[1].nUserCount.ToString();
                    cmb_UserNum2.SelectedIndex = 0;
                }
                if (cfg_info.nGroup >= 3)
                {
                    txt_Num3.Text = cfg_info.stuGroupInfo[2].nUserCount.ToString();
                    cmb_UserNum3.SelectedIndex = 0;
                }
                if (cfg_info.nGroup >= 4)
                {
                    txt_Num4.Text = cfg_info.stuGroupInfo[3].nUserCount.ToString();
                    cmb_UserNum4.SelectedIndex = 0;
                }
            }
        }

        private void btn_Get_Click(object sender, EventArgs e)
        {
            UIClear();
            if (GetConfig())
            {
                MessageBox.Show("Get successfully()");

                if (cfg_info.nGroup >= 1)
                {
                    txt_Num1.Text = cfg_info.stuGroupInfo[0].nUserCount.ToString();
                    cmb_UserNum1.SelectedIndex = 0;
                }
                if (cfg_info.nGroup >= 2)
                {
                    txt_Num2.Text = cfg_info.stuGroupInfo[1].nUserCount.ToString();
                    cmb_UserNum2.SelectedIndex = 0;
                }
                if (cfg_info.nGroup >= 3)
                {
                    txt_Num3.Text = cfg_info.stuGroupInfo[2].nUserCount.ToString();
                    cmb_UserNum3.SelectedIndex = 0;
                }
                if (cfg_info.nGroup >= 4)
                {
                    txt_Num4.Text = cfg_info.stuGroupInfo[3].nUserCount.ToString();
                    cmb_UserNum4.SelectedIndex = 0;
                }
            }
        }

        private void btn_Set_Click(object sender, EventArgs e)
        {
            try
            {
                int num1 = 0;
                int num2 = 0;
                int num3 = 0;
                int num4 = 0;
                if (!string.IsNullOrEmpty(txt_Num1.Text.Trim()))
                {
                    num1 = int.Parse(txt_Num1.Text);
                }
                if (!string.IsNullOrEmpty(txt_Num2.Text.Trim()))
                {
                    num2 = int.Parse(txt_Num2.Text);
                }
                if (!string.IsNullOrEmpty(txt_Num3.Text.Trim()))
                {
                    num3 = int.Parse(txt_Num3.Text);
                }
                if (!string.IsNullOrEmpty(txt_Num4.Text.Trim()))
                {
                    num4 = int.Parse(txt_Num4.Text);
                }

                if (num1 + num2 + num3 + num4 > 5)
                {
                    MessageBox.Show("The effective number cannot be greater than 5(5)！");
                    return;
                }

                cfg_info.stuGroupInfo[0].nUserCount = num1;
                cfg_info.stuGroupInfo[1].nUserCount = num2;
                cfg_info.stuGroupInfo[2].nUserCount = num3;
                cfg_info.stuGroupInfo[3].nUserCount = num4;

                int select_num = cmb_UserNum1.SelectedIndex;
                if (select_num >= 0)
                {
                    cfg_info.stuGroupInfo[0].stuGroupDetail[select_num].szUserID = txt_UserID1.Text;
                    cfg_info.stuGroupInfo[0].stuGroupDetail[select_num].emMethod = GetOpenMethodEnum(cmb_OpenMethod1.SelectedIndex);
                }

                select_num = cmb_UserNum2.SelectedIndex;
                if (select_num >= 0)
                {
                    cfg_info.stuGroupInfo[1].stuGroupDetail[select_num].szUserID = txt_UserID2.Text;
                    cfg_info.stuGroupInfo[1].stuGroupDetail[select_num].emMethod = GetOpenMethodEnum(cmb_OpenMethod2.SelectedIndex);
                }

                select_num = cmb_UserNum3.SelectedIndex;
                if (select_num >= 0)
                {
                    cfg_info.stuGroupInfo[2].stuGroupDetail[select_num].szUserID = txt_UserID3.Text;
                    cfg_info.stuGroupInfo[2].stuGroupDetail[select_num].emMethod = GetOpenMethodEnum(cmb_OpenMethod3.SelectedIndex);
                }

                select_num = cmb_UserNum4.SelectedIndex;
                if (select_num >= 0)
                {
                    cfg_info.stuGroupInfo[3].stuGroupDetail[select_num].szUserID = txt_UserID4.Text;
                    cfg_info.stuGroupInfo[3].stuGroupDetail[select_num].emMethod = GetOpenMethodEnum(cmb_OpenMethod4.SelectedIndex);
                }

                for (int i = 0; i < cfg_info.stuGroupInfo[0].stuGroupDetail.Length; i++)
                {
                    if (string.IsNullOrEmpty(cfg_info.stuGroupInfo[0].stuGroupDetail[i].szUserID))
                    {
                        cfg_info.stuGroupInfo[0].nGroupNum = i;
                        break;
                    }
                }
              
                for (int i = 0; i < cfg_info.stuGroupInfo[1].stuGroupDetail.Length; i++)
                {
                    if (string.IsNullOrEmpty(cfg_info.stuGroupInfo[1].stuGroupDetail[i].szUserID))
                    {
                        cfg_info.stuGroupInfo[1].nGroupNum = i;
                        break;
                    }
                }
               
                for (int i = 0; i < cfg_info.stuGroupInfo[2].stuGroupDetail.Length; i++)
                {
                    if (string.IsNullOrEmpty(cfg_info.stuGroupInfo[2].stuGroupDetail[i].szUserID))
                    {
                        cfg_info.stuGroupInfo[2].nGroupNum = i;
                        break;
                    }
                }
              
                for (int i = 0; i < cfg_info.stuGroupInfo[3].stuGroupDetail.Length; i++)
                {
                    if (string.IsNullOrEmpty(cfg_info.stuGroupInfo[3].stuGroupDetail[i].szUserID))
                    {
                        cfg_info.stuGroupInfo[3].nGroupNum = i;
                        break;
                    }
                }

                if (cfg_info.stuGroupInfo[3].nGroupNum > 0)
                {
                    cfg_info.nGroup = 4;
                }
                else if (cfg_info.stuGroupInfo[2].nGroupNum > 0)
                {
                    cfg_info.nGroup = 3;
                }
                else if (cfg_info.stuGroupInfo[1].nGroupNum > 0)
                {
                    cfg_info.nGroup = 2;
                }
                else if (cfg_info.stuGroupInfo[0].nGroupNum > 0)
                {
                    cfg_info.nGroup = 1;
                }
                else
                {
                    cfg_info.nGroup = 0;
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                return;
            }

            if (SetConfig(cfg_info))
            {
                MessageBox.Show("Set successfully()");
            }
        }

        public bool GetConfig()
        {
            bool bRet = false;
            try
            {
                cfg_info.stuGroupInfo = new NET_CFG_OPEN_DOOR_GROUP[4];
                for (int i = 0; i < cfg_info.stuGroupInfo.Length; i++)
                {
                    cfg_info.stuGroupInfo[i].stuGroupDetail = new NET_CFG_OPEN_DOOR_GROUP_DETAIL[64];
                }
                
                object objTemp = cfg_info;
                bRet = NETClient.GetNewDevConfig(m_LoginID, cmb_Channel.SelectedIndex, CFG_CMD_OPEN_DOOR_GROUP, ref objTemp, typeof(NET_CFG_OPEN_DOOR_GROUP_INFO), 10000);
                if (bRet)
                {
                    cfg_info = (NET_CFG_OPEN_DOOR_GROUP_INFO)objTemp;
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

        public bool SetConfig(NET_CFG_OPEN_DOOR_GROUP_INFO cfg)
        {
            bool bRet = false;
            try
            {
                bRet = NETClient.SetNewDevConfig(m_LoginID, cmb_Channel.SelectedIndex, CFG_CMD_OPEN_DOOR_GROUP, (object)cfg, typeof(NET_CFG_OPEN_DOOR_GROUP_INFO), 5000);
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

        public int GetOpenMethodSelectedIndex(EM_CFG_OPEN_DOOR_GROUP_METHOD em)
        {
            int result = 0;
            switch (em)
            {
                case EM_CFG_OPEN_DOOR_GROUP_METHOD.CARD:
                    result = 1;
                    break;
                case EM_CFG_OPEN_DOOR_GROUP_METHOD.PWD:
                    result = 2;
                    break;
                case EM_CFG_OPEN_DOOR_GROUP_METHOD.FINGERPRINT:
                    result = 3;
                    break;
                case EM_CFG_OPEN_DOOR_GROUP_METHOD.FACE:
                    result = 4;
                    break;
                default:
                    result = 0;
                    break;
            }
            return result;
        }

        public EM_CFG_OPEN_DOOR_GROUP_METHOD GetOpenMethodEnum(int index)
        {
            EM_CFG_OPEN_DOOR_GROUP_METHOD value = EM_CFG_OPEN_DOOR_GROUP_METHOD.UNKNOWN;
            switch (index)
            {
                case 1:
                    value = EM_CFG_OPEN_DOOR_GROUP_METHOD.CARD;
                    break;
                case 2:
                    value = EM_CFG_OPEN_DOOR_GROUP_METHOD.PWD;
                    break;
                case 3:
                    value = EM_CFG_OPEN_DOOR_GROUP_METHOD.FINGERPRINT;
                    break;
                case 4:
                    value = EM_CFG_OPEN_DOOR_GROUP_METHOD.FACE;
                    break;
                default:
                    value = EM_CFG_OPEN_DOOR_GROUP_METHOD.UNKNOWN;
                    break;
            }
            return value;
        }

        private void cmb_UserNum1_SelectedIndexChanged(object sender, EventArgs e)
        {
            int num = cmb_UserNum1.SelectedIndex;
            if (num < 0)
            {
                return;
            }
            if (cfg_info.nGroup >= 1)
            {
                txt_UserID1.Text = cfg_info.stuGroupInfo[0].stuGroupDetail[num].szUserID;
                cmb_OpenMethod1.SelectedIndex = GetOpenMethodSelectedIndex(cfg_info.stuGroupInfo[0].stuGroupDetail[num].emMethod);
            }
        }

        private void cmb_UserNum2_SelectedIndexChanged(object sender, EventArgs e)
        {
            int num = cmb_UserNum2.SelectedIndex;
            if (num < 0)
            {
                return;
            }
            if (cfg_info.nGroup >= 2)
            {
                txt_UserID2.Text = cfg_info.stuGroupInfo[1].stuGroupDetail[num].szUserID;
                cmb_OpenMethod2.SelectedIndex = GetOpenMethodSelectedIndex(cfg_info.stuGroupInfo[1].stuGroupDetail[num].emMethod);
            }
        }

        private void cmb_UserNum3_SelectedIndexChanged(object sender, EventArgs e)
        {
            int num = cmb_UserNum3.SelectedIndex;
            if (num < 0)
            {
                return;
            }
            if (cfg_info.nGroup >= 3)
            {
                txt_UserID3.Text = cfg_info.stuGroupInfo[2].stuGroupDetail[num].szUserID;
                cmb_OpenMethod3.SelectedIndex = GetOpenMethodSelectedIndex(cfg_info.stuGroupInfo[2].stuGroupDetail[num].emMethod);
            }
        }

        private void cmb_UserNum4_SelectedIndexChanged(object sender, EventArgs e)
        {
            int num = cmb_UserNum4.SelectedIndex;
            if (num < 0)
            {
                return;
            }
            if (cfg_info.nGroup >= 4)
            {
                txt_UserID4.Text = cfg_info.stuGroupInfo[3].stuGroupDetail[num].szUserID;
                cmb_OpenMethod4.SelectedIndex = GetOpenMethodSelectedIndex(cfg_info.stuGroupInfo[3].stuGroupDetail[num].emMethod);
            }
        }

        public void UIClear()
        {
            txt_Num1.Text = "";
            txt_Num2.Text = "";
            txt_Num3.Text = "";
            txt_Num4.Text = "";
            txt_UserID1.Text = "";
            txt_UserID2.Text = "";
            txt_UserID3.Text = "";
            txt_UserID4.Text = "";
            cmb_UserNum1.SelectedIndex = -1;
            cmb_UserNum2.SelectedIndex = -1;
            cmb_UserNum3.SelectedIndex = -1;
            cmb_UserNum4.SelectedIndex = -1;
            cmb_OpenMethod1.SelectedIndex = -1;
            cmb_OpenMethod2.SelectedIndex = -1;
            cmb_OpenMethod3.SelectedIndex = -1;
            cmb_OpenMethod4.SelectedIndex = -1;
        }
    }
}
