using NetSDKCS;
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AccessDemo2s
{
    public partial class UserManageFrom : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;
        private int m_Channel = 0;
        private IntPtr m_EigenLoginID = IntPtr.Zero;

        private IntPtr m_FindUserID = IntPtr.Zero;
        private List<NET_ACCESS_USER_INFO> userInfoList = new List<NET_ACCESS_USER_INFO>();
        private int QueryNum = 10;

        private NET_ACCESS_FACE_INFO m_stuFaceInfo = new NET_ACCESS_FACE_INFO();
        private NET_ACCESS_USER_INFO m_stuUserInfo = new NET_ACCESS_USER_INFO();
        private NET_ACCESS_CARD_INFO[] m_stuCardInfos;

        public UserManageFrom(IntPtr loginid, int channel)
        {
            InitializeComponent();
            if (IntPtr.Zero != loginid)
            {
                m_LoginID = loginid;
            }
            m_Channel = channel;
            m_stuFaceInfo.nFacePhoto = 1;
            m_stuFaceInfo.nInFacePhotoLen = new int[5];
            m_stuFaceInfo.nInFacePhotoLen[0] = 1024 * 1024;
            m_stuFaceInfo.pFacePhoto = new IntPtr[5];
            m_stuFaceInfo.pFacePhoto[0] = Marshal.AllocHGlobal(1024 * 1024);
            m_stuFaceInfo.bFaceDataExEnable = true;
            m_stuFaceInfo.nMaxFaceDataLen = new int[20];
            m_stuFaceInfo.nMaxFaceDataLen[0] = 1024 * 1024;
            m_stuFaceInfo.pFaceDataEx = new IntPtr[20];
            m_stuFaceInfo.pFaceDataEx[0] = Marshal.AllocHGlobal(1024 * 1024);
            m_stuFaceInfo.pEigenData = new IntPtr[5];
            this.Load += new EventHandler(UpdateDisableButton);
            this.Closed += new EventHandler(UserManageFrom_Closed);
        }

        void UpdateDisableButton(object sender, EventArgs e)
        {
            NET_CFG_LEASE_RULES_INFO stRulesInfo = new NET_CFG_LEASE_RULES_INFO();
            stRulesInfo.dwSize = (uint)Marshal.SizeOf(typeof(NET_CFG_LEASE_RULES_INFO));
            object obj = (object)stRulesInfo;
            try
            {
                bool ret = NETClient.GetOperateConfig(m_LoginID, EM_CFG_OPERATE_TYPE.LEASE_RULES, -1, ref obj, typeof(NET_CFG_LEASE_RULES_INFO), 3000);
                if (ret)
                {
                    NET_CFG_LEASE_RULES_INFO stRetRulesInfo = (NET_CFG_LEASE_RULES_INFO)obj;
                    UpdateButtonGroup(stRetRulesInfo.bEnable);
                }
            }
            catch (Exception exp)
            {
                MessageBox.Show(exp.Message);
                return;
            }
        }

        void UserManageFrom_Closed(object sender, EventArgs e)
        {
            if (m_EigenLoginID != IntPtr.Zero)
            {
                NETClient.Logout(m_EigenLoginID);
                m_EigenLoginID = IntPtr.Zero;
            }
            Marshal.FreeHGlobal(m_stuFaceInfo.pFacePhoto[0]);
            Marshal.FreeHGlobal(m_stuFaceInfo.pFaceDataEx[0]);
        }

        private void UserManageFrom_Load(object sender, EventArgs e)
        {
            GetAllUser();
        }

        private void btn_Get_Click(object sender, EventArgs e)
        {
            GetAllUser();
        }

        private void btn_Add_Click(object sender, EventArgs e)
        {
            UserInfoForm userInfo = new UserInfoForm(m_LoginID, EM_OperateType.Add, new NET_ACCESS_USER_INFO(), m_Channel);
            userInfo.ShowDialog();
            userInfo.Dispose();
            GetAllUser();
        }

        private void btn_Modify_Click(object sender, EventArgs e)
        {
            if (dataGridView_user.SelectedRows.Count == 0)
            {
                MessageBox.Show("Please select one user!(！)");
                return;
            }
            string user_ID = dataGridView_user.SelectedRows[0].Cells[1].Value.ToString();

            var infolist = userInfoList.Where(a => a.szUserID==user_ID).ToList();
            if (infolist.Count != 1)
            {
                MessageBox.Show("The select data is error!(！)");
                return;
            }
            var select_user_info = infolist[0];
            UserInfoForm userInfo = new UserInfoForm(m_LoginID, EM_OperateType.Modify, select_user_info, m_Channel);
            userInfo.ShowDialog();
            userInfo.Dispose();
            GetAllUser();
        }

        private void btn_Delete_Click(object sender, EventArgs e)
        {
            if (dataGridView_user.SelectedRows.Count == 0)
            {
                MessageBox.Show("Please select one user!(！)");
                return;
            }
            string user_ID = dataGridView_user.SelectedRows[0].Cells[1].Value.ToString();

            var infolist = userInfoList.Where(a => a.szUserID == user_ID).ToList();
            if (infolist.Count != 1)
            {
                MessageBox.Show("The select data is error!(！)");
                return;
            }
            var select_user_info = infolist[0];
            NET_EM_FAILCODE[] stuOutErrArray = new NET_EM_FAILCODE[1];
            string[] InUserid = new string[] { select_user_info.szUserID };
            bool result = NETClient.RemoveOperateAccessUserService(m_LoginID, InUserid, out stuOutErrArray, 3000);
            if (!result)
            {
                for (int i = 0; i < stuOutErrArray.Length; i++)
                {
                    MessageBox.Show(GetFailCodeMsg(stuOutErrArray[i].emCode));
                }
            }
            GetAllUser();
        }

        private void GetAllUser()
        {
            NET_IN_USERINFO_START_FIND stuStartIn = new NET_IN_USERINFO_START_FIND();
            stuStartIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_USERINFO_START_FIND));

            NET_OUT_USERINFO_START_FIND stuStartOut = new NET_OUT_USERINFO_START_FIND();
            stuStartOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_USERINFO_START_FIND));
            stuStartOut.nTotalCount = 0;
            stuStartOut.nCapNum = 50;
            m_FindUserID = NETClient.StartFindUserInfo(m_LoginID, ref stuStartIn, ref stuStartOut, 5000);
            if (IntPtr.Zero == m_FindUserID)
            {
                MessageBox.Show(NETClient.GetLastError());
                return;
            }

            userInfoList.Clear();

            NET_IN_USERINFO_DO_FIND stuFindIn = new NET_IN_USERINFO_DO_FIND();
            stuFindIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_USERINFO_DO_FIND));
            stuFindIn.nCount = QueryNum;

            NET_OUT_USERINFO_DO_FIND stuFindOut = new NET_OUT_USERINFO_DO_FIND();
            stuFindOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_USERINFO_DO_FIND));
            stuFindOut.nMaxNum = QueryNum;

            NET_ACCESS_USER_INFO[] stuOutUserInfo = new NET_ACCESS_USER_INFO[stuFindOut.nMaxNum];
            IntPtr outInfo = IntPtr.Zero;
            outInfo = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_ACCESS_USER_INFO)) * stuFindOut.nMaxNum);
            for (int index = 0; index < stuFindOut.nMaxNum; index++)
            {
                IntPtr outInfoIndex = outInfo + index * Marshal.SizeOf(typeof(NET_ACCESS_USER_INFO));
                if (stuOutUserInfo[index].GetType() == typeof(NET_ACCESS_USER_INFO))                            //if obj is boxinged type of typeName, some param(ex. dwsize) need trans to unmanaged memory
                {
                    Marshal.StructureToPtr(stuOutUserInfo[index], outInfoIndex, true);
                }
                else
                {
                    for (int i = 0; i < Marshal.SizeOf(typeof(NET_ACCESS_USER_INFO)); i++)
                    {
                        Marshal.WriteByte(outInfoIndex, i, 0);
                    }
                }
            }
            stuFindOut.pstuInfo = outInfo;

            int startNum = 0;
            while (true)
            {
                stuFindIn.nStartNo = startNum;

                bool result = NETClient.DoFindUserInfo(m_FindUserID, ref stuFindIn, ref stuFindOut, 5000);
                if (!result)
                {
                    break;
                }

                if (stuFindOut.nRetNum > 0)
                {
                    startNum += stuFindOut.nRetNum;
                    for (int i = 0; i < stuFindOut.nRetNum; i++)
                    {
                        var userinfo = (NET_ACCESS_USER_INFO)Marshal.PtrToStructure(IntPtr.Add(stuFindOut.pstuInfo, Marshal.SizeOf(typeof(NET_ACCESS_USER_INFO)) * i), typeof(NET_ACCESS_USER_INFO));
                        userInfoList.Add(userinfo);
                    }
                }
            }
            NETClient.StopFindUserInfo(m_FindUserID);
            Marshal.FreeHGlobal(outInfo);

            ShowInGridView();
        }

        private void ShowInGridView()
        {
            this.BeginInvoke(new Action(() =>
            {
                dataGridView_user.Rows.Clear();
                int index = 0;
                foreach (var item in userInfoList)
                {
                    index++;
                    DataGridViewRow row = new DataGridViewRow();
                    DataGridViewTextBoxCell cell1 = new DataGridViewTextBoxCell();
                    cell1.Value = index.ToString();
                    row.Cells.Add(cell1);
                    DataGridViewTextBoxCell cell2 = new DataGridViewTextBoxCell();
                    cell2.Value = item.szUserID;
                    row.Cells.Add(cell2);
                    DataGridViewTextBoxCell cell3 = new DataGridViewTextBoxCell();
                    cell3.Value = item.szName;
                    row.Cells.Add(cell3);
                    DataGridViewTextBoxCell cell4 = new DataGridViewTextBoxCell();
                    string value = "";
                    switch (item.emUserType)
                    {
                        case EM_USER_TYPE.NORMAL:
                            value = "NORMAL()";
                            break;
                        case EM_USER_TYPE.BLACKLIST:
                            value = "No permission List()";
                            break;
                        case EM_USER_TYPE.GUEST:
                            value = "GUEST()";
                            break;
                        case EM_USER_TYPE.PATROL:
                            value = "PATROL()";
                            break;
                        case EM_USER_TYPE.VIP:
                            value = "V/P(V/P)";
                            break;
                        case EM_USER_TYPE.HANDICAP:
                            value = "HANDICAP(CJ)";
                            break;
                        case EM_USER_TYPE.CUSTOM1:
                            value = "CUSTOM1(1)";
                            break;
                        case EM_USER_TYPE.CUSTOM2:
                            value = "CUSTOM2(2)";
                            break;
                        case EM_USER_TYPE.UNKNOWN:
                            value = "UNKNOWN()";
                            break;
                        default:
                            value = "UNKNOWN()";
                            break;
                    }
                    cell4.Value = value;
                    row.Cells.Add(cell4);
                    dataGridView_user.Rows.Add(row);
                }

            }));
        }

        private string GetFailCodeMsg(EM_FAILCODE em)
        {
            string failMsg = "";
            switch (em)
            {
                case EM_FAILCODE.NOERROR:
                    break;
                case EM_FAILCODE.UNKNOWN:
                    failMsg = "UNKNOWN()";
                    break;
                case EM_FAILCODE.INVALID_PARAM:
                    failMsg = "INVALID_PARAM()";
                    break;
                case EM_FAILCODE.INVALID_PASSWORD:
                    failMsg = "INVALID_PASSWORD()";
                    break;
                case EM_FAILCODE.INVALID_FP:
                    failMsg = "INVALID_FP(ZW)";
                    break;
                case EM_FAILCODE.INVALID_FACE:
                    failMsg = "INVALID_FACE()";
                    break;
                case EM_FAILCODE.INVALID_CARD:
                    failMsg = "INVALID_CARD()";
                    break;
                case EM_FAILCODE.INVALID_USER:
                    failMsg = "INVALID_USER()";
                    break;
                case EM_FAILCODE.FAILED_GET_SUBSERVICE:
                    failMsg = "FAILED_GET_SUBSERVICE()";
                    break;
                case EM_FAILCODE.FAILED_GET_METHOD:
                    failMsg = "FAILED_GET_METHOD()";
                    break;
                case EM_FAILCODE.FAILED_GET_SUBCAPS:
                    failMsg = "FAILED_GET_SUBCAPS()";
                    break;
                case EM_FAILCODE.ERROR_INSERT_LIMIT:
                    failMsg = "ERROR_INSERT_LIMIT()";
                    break;
                case EM_FAILCODE.ERROR_MAX_INSERT_RATE:
                    failMsg = "ERROR_MAX_INSERT_RATE()";
                    break;
                case EM_FAILCODE.FAILED_ERASE_FP:
                    failMsg = "FAILED_ERASE_FP(ZW)";
                    break;
                case EM_FAILCODE.FAILED_ERASE_FACE:
                    failMsg = "FAILED_ERASE_FACE()";
                    break;
                case EM_FAILCODE.FAILED_ERASE_CARD:
                    failMsg = "FAILED_ERASE_CARD()";
                    break;
                case EM_FAILCODE.NO_RECORD:
                    failMsg = "NO_RECORD()";
                    break;
                case EM_FAILCODE.NOMORE_RECORD:
                    failMsg = "NOMORE_RECORD(，)";
                    break;
                case EM_FAILCODE.RECORD_ALREADY_EXISTS:
                    failMsg = "RECORD_ALREADY_EXISTS(ZW，)";
                    break;
                case EM_FAILCODE.MAX_FP_PERUSER:
                    failMsg = "MAX_FP_PERUSER(ZW)";
                    break;
                case EM_FAILCODE.MAX_CARD_PERUSER:
                    failMsg = "MAX_CARD_PERUSER()";
                    break;
                case EM_FAILCODE.EXCEED_MAX_PHOTOSIZE:
                    failMsg = "EXCEED_MAX_PHOTOSIZE()";
                    break;
                case EM_FAILCODE.INVALID_USERID:
                    failMsg = "INVALID_USERID(ID())";
                    break;
                case EM_FAILCODE.EXTRACTFEATURE_FAIL:
                    failMsg = "EXTRACTFEATURE_FAIL()";
                    break;
                case EM_FAILCODE.PHOTO_EXIST:
                    failMsg = "PHOTO_EXIST()";
                    break;
                case EM_FAILCODE.PHOTO_OVERFLOW:
                    failMsg = "PHOTO_OVERFLOW()";
                    break;
                case EM_FAILCODE.INVALID_PHOTO_FORMAT:
                    failMsg = "INVALID_PHOTO_FORMAT()";
                    break;
                case EM_FAILCODE.EXCEED_ADMINISTRATOR_LIMIT:
                    failMsg = "EXCEED_ADMINISTRATOR_LIMIT()";
                    break;
                default:
                    failMsg = "UNKNOWN()";
                    break;
            }
            return failMsg;
        }

        private void button_GetEigen_Click(object sender, EventArgs e)
        {
            if (dataGridView_user.SelectedRows.Count == 0)
            {
                MessageBox.Show("Please select one user!(！)");
                return;
            }
            string user_ID = dataGridView_user.SelectedRows[0].Cells[1].Value.ToString();

            IntPtr p_stuIn = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_IN_ACCESS_FACE_SERVICE_GET)));
            NET_IN_ACCESS_FACE_SERVICE_GET stuIn = new NET_IN_ACCESS_FACE_SERVICE_GET();
            stuIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_ACCESS_FACE_SERVICE_GET));
            stuIn.nUserNum = 1;
            stuIn.szUserID = new NET_IN_ACCESS_FACE_SERVICE_UserID[100];
            stuIn.szUserID[0].userID = user_ID;
            Marshal.StructureToPtr(stuIn, p_stuIn, true);

            IntPtr p_stuOut = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_OUT_ACCESS_FACE_SERVICE_GET)));
            NET_OUT_ACCESS_FACE_SERVICE_GET stuOut = new NET_OUT_ACCESS_FACE_SERVICE_GET();
            stuOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_ACCESS_FACE_SERVICE_GET));
            stuOut.nMaxRetNum = 1;
            stuOut.pFaceInfo = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_ACCESS_FACE_INFO)));
            Marshal.StructureToPtr(m_stuFaceInfo, stuOut.pFaceInfo, true);
            stuOut.pFailCode = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_EM_FAILCODE)));
            Marshal.StructureToPtr(stuOut, p_stuOut, true);
            try
            {
                bool result = NETClient.OperateAccessFaceService(m_LoginID, EM_NET_ACCESS_CTL_FACE_SERVICE.GET, p_stuIn, p_stuOut, 5000);
                if (!result)
                {

                    MessageBox.Show("Get eigenvalue failed:" + NETClient.GetLastError());
                }
                else
                {
                    stuOut = (NET_OUT_ACCESS_FACE_SERVICE_GET)Marshal.PtrToStructure(p_stuOut, typeof(NET_OUT_ACCESS_FACE_SERVICE_GET));
                    m_stuFaceInfo = (NET_ACCESS_FACE_INFO)Marshal.PtrToStructure(stuOut.pFaceInfo, typeof(NET_ACCESS_FACE_INFO));
                    MessageBox.Show("Get eigenvalue success!");
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            finally
            {
                Marshal.FreeHGlobal(stuOut.pFailCode);
                Marshal.FreeHGlobal(stuOut.pFaceInfo);
                Marshal.FreeHGlobal(p_stuIn);
                Marshal.FreeHGlobal(p_stuOut);
            }
        }

        private void button_AddEigen_Click(object sender, EventArgs e)
        {
            if (m_EigenLoginID == IntPtr.Zero)
            {
                MessageBox.Show("Please login first!");
                return;
            }

            if (dataGridView_user.SelectedRows.Count == 0)
            {
                MessageBox.Show("Please select one user!(！)");
                return;
            }
            else if (m_stuFaceInfo.nOutFacePhotoLen == null)
            {
                MessageBox.Show("Not get eigen info!()");
                return;
            }

            string user_ID = dataGridView_user.SelectedRows[0].Cells[1].Value.ToString();

            IntPtr inFacePtr = IntPtr.Zero;
            IntPtr outFacePtr = IntPtr.Zero;

            NET_IN_ADD_FACE_INFO inAddFaceInfo = new NET_IN_ADD_FACE_INFO();
            inAddFaceInfo.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_ADD_FACE_INFO));
            inAddFaceInfo.szUserID = user_ID;
            inAddFaceInfo.stuFaceInfo = new NET_FACE_RECORD_INFO();

            //
            inAddFaceInfo.stuFaceInfo.nFacePhoto = 1;
            inAddFaceInfo.stuFaceInfo.nFacePhotoLen = new int[5];

            int nPhotoLen = m_stuFaceInfo.nOutFacePhotoLen[0];
            inAddFaceInfo.stuFaceInfo.nFacePhotoLen[0] = nPhotoLen;
            inAddFaceInfo.stuFaceInfo.pszFacePhoto = new IntPtr[5];
            inAddFaceInfo.stuFaceInfo.pszFacePhoto[0] = Marshal.AllocHGlobal(nPhotoLen);

            byte[] byFacePhoto = new byte[nPhotoLen];
            Marshal.Copy(m_stuFaceInfo.pFacePhoto[0], byFacePhoto, 0, nPhotoLen);
            Marshal.Copy(byFacePhoto, 0, inAddFaceInfo.stuFaceInfo.pszFacePhoto[0], nPhotoLen);

            //
            inAddFaceInfo.stuFaceInfo.nFaceData = m_stuFaceInfo.nFaceData;
            inAddFaceInfo.stuFaceInfo.nFaceDataLen = new int[20];
            inAddFaceInfo.stuFaceInfo.szFaceData = new byte[20 * 2048];
            for (int i = 0; i < m_stuFaceInfo.nFaceData; i++)
            {
                string base16Str = BitConverter.ToString(m_stuFaceInfo.szFaceData, i * 2048, m_stuFaceInfo.nFaceDataLen[i]).Replace("-", "");
                byte[] base16byte = Encoding.Default.GetBytes(base16Str);
                Array.Copy(base16byte, 0, inAddFaceInfo.stuFaceInfo.szFaceData, i * 2048, base16byte.Length);
                inAddFaceInfo.stuFaceInfo.nFaceDataLen[i] = base16byte.Length;
            }

            inFacePtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_IN_ADD_FACE_INFO)));
            Marshal.StructureToPtr(inAddFaceInfo, inFacePtr, true);

            outFacePtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_OUT_ADD_FACE_INFO)));
            NET_OUT_ADD_FACE_INFO outAddFaceInfo = new NET_OUT_ADD_FACE_INFO();
            outAddFaceInfo.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_ADD_FACE_INFO));
            Marshal.StructureToPtr(outAddFaceInfo, outFacePtr, true);

            try
            {
                bool bRet = NETClient.FaceInfoOpreate(m_EigenLoginID, EM_FACEINFO_OPREATE_TYPE.ADD, inFacePtr, outFacePtr, 5000);
                if (!bRet)
                {

                    string AA= NETClient.GetLastError().ToString().Trim();

                    if(AA.Contains("photo exist"))
                    {
                        bRet = NETClient.FaceInfoOpreate(m_EigenLoginID, EM_FACEINFO_OPREATE_TYPE.UPDATE, inFacePtr, outFacePtr, 5000);
                        if (!bRet)
                        {
                            MessageBox.Show("Add eigenvalue failed:" + NETClient.GetLastError());
                        }
                    
                    
                    }


                    
                }
                else
                {
                    MessageBox.Show("Add eigenvalue success!");
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            finally
            {
                Marshal.FreeHGlobal(inAddFaceInfo.stuFaceInfo.pszFacePhoto[0]);
                Marshal.FreeHGlobal(inFacePtr);
                Marshal.FreeHGlobal(outFacePtr);
            }
        }

        private void button_LogInEigenDevice_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero == m_EigenLoginID)
            {
                ushort port = 0;
                try
                {
                    port = Convert.ToUInt16(port_textBox.Text.Trim());
                }
                catch
                {
                    MessageBox.Show("Input port error");
                    return;
                }
                NET_DEVICEINFO_Ex deviceInfo = new NET_DEVICEINFO_Ex();
                m_EigenLoginID = NETClient.LoginWithHighLevelSecurity(ip_textBox.Text.Trim(), port, user_textBox.Text.Trim(), pwd_textBox.Text.Trim(), EM_LOGIN_SPAC_CAP_TYPE.TCP, IntPtr.Zero, ref deviceInfo);
                if (IntPtr.Zero == m_EigenLoginID)
                {
                    MessageBox.Show(this, NETClient.GetLastError());
                    return;
                }
                button_LogInEigenDevice.Text = "Logout";
            }
            else
            {
                if (IntPtr.Zero != m_EigenLoginID)
                {
                    bool result = NETClient.Logout(m_EigenLoginID);
                    if (!result)
                    {
                        MessageBox.Show(this, NETClient.GetLastError());
                    }
                }
                m_EigenLoginID = IntPtr.Zero;
                button_LogInEigenDevice.Text = "LogInEigenDevice";
            }
        }

        private void button_GetUserInfo_Click(object sender, EventArgs e)
        {
            if (dataGridView_user.SelectedRows.Count == 0)
            {
                MessageBox.Show("Please select one user!(！)");
                return;
            }

            try
            {
                string[] userids = new string[1];
                userids[0] = dataGridView_user.SelectedRows[0].Cells[1].Value.ToString();
                NET_ACCESS_USER_INFO[] stOutParam1;
                NET_EM_FAILCODE[] stOutParam2;
                bool result = NETClient.GetOperateAccessUserService(m_LoginID, userids, out stOutParam1, out stOutParam2, 5000);
                if (!result)
                {
                    MessageBox.Show("Get user info failed:" + NETClient.GetLastError());
                }
                else
                {
                    m_stuUserInfo = stOutParam1[0];
                    MessageBox.Show("Get user info success!");
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
        }

        private void button_GetCardInfo_Click(object sender, EventArgs e)
        {
            if (dataGridView_user.SelectedRows.Count == 0)
            {
                MessageBox.Show("Please select one user!(！)");
                return;
            }

            try
            {
                NET_IN_CARDINFO_START_FIND stuStartIn = new NET_IN_CARDINFO_START_FIND();
                stuStartIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_CARDINFO_START_FIND));
                stuStartIn.szUserID = dataGridView_user.SelectedRows[0].Cells[1].Value.ToString();
                NET_OUT_CARDINFO_START_FIND stuStartOut = new NET_OUT_CARDINFO_START_FIND();
                stuStartOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_CARDINFO_START_FIND));
                stuStartOut.nTotalCount = 0;
                stuStartOut.nCapNum = 10;
                List<string> cardIdList = new List<string>();
                IntPtr cardFindId = NETClient.StartFindCardInfo(m_LoginID, ref stuStartIn, ref stuStartOut, 5000);
                if (IntPtr.Zero != cardFindId)
                {
                    int nStartNo = 0;
                    bool m_bIsDoFindNextCard = true;
                    while (m_bIsDoFindNextCard)
                    {
                        int nRecordNum = 0;
                        NET_IN_CARDINFO_DO_FIND stuFindIn = new NET_IN_CARDINFO_DO_FIND();
                        stuFindIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_CARDINFO_DO_FIND));
                        stuFindIn.nStartNo = nStartNo;
                        stuFindIn.nCount = 10;
                        NET_OUT_CARDINFO_DO_FIND stuFindOut = new NET_OUT_CARDINFO_DO_FIND();
                        stuFindOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_CARDINFO_DO_FIND));
                        stuFindOut.nMaxNum = 10;
                        stuFindOut.pstuInfo = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_ACCESS_CARD_INFO)) * stuFindOut.nMaxNum); ;
                        NET_ACCESS_CARD_INFO[] pCardInfo = new NET_ACCESS_CARD_INFO[stuFindOut.nMaxNum];
                        for (int i = 0; i < stuFindOut.nMaxNum; i++)
                        {
                            IntPtr pDst = IntPtr.Add(stuFindOut.pstuInfo, Marshal.SizeOf(typeof(NET_ACCESS_CARD_INFO)) * i);
                            Marshal.StructureToPtr(pCardInfo[i], pDst, true);
                        }

                        bool continueFlag = true;
                        bool ret = NETClient.DoFindCardInfo(cardFindId, ref stuFindIn, ref stuFindOut, 5000);
                        if (ret)
                        {
                            if (stuFindOut.nRetNum > 0)
                            {
                                nRecordNum = stuFindOut.nRetNum;
                                for (int i = 0; i < nRecordNum; i++)
                                {
                                    IntPtr pDst = IntPtr.Add(stuFindOut.pstuInfo, Marshal.SizeOf(typeof(NET_ACCESS_CARD_INFO)) * i);
                                    NET_ACCESS_CARD_INFO stuInfo = (NET_ACCESS_CARD_INFO)Marshal.PtrToStructure(pDst, typeof(NET_ACCESS_CARD_INFO));
                                    cardIdList.Add(stuInfo.szCardNo);
                                }
                            }

                            if (nRecordNum < 10)
                            {
                                continueFlag = false;
                            }
                            else
                            {
                                nStartNo += nRecordNum;
                            }
                        }
                        else
                        {
                            continueFlag = false;
                        }

                        Marshal.FreeHGlobal(stuFindOut.pstuInfo);
                        if (!continueFlag)
                        {
                            break;
                        }
                    }
                    NETClient.StopFindCardInfo(cardFindId);
                }
                else
                {
                    MessageBox.Show(NETClient.GetLastError());
                }

                string[] cardids = cardIdList.ToArray();
                if (cardids.Length <= 0)
                {
                    MessageBox.Show("current user not have card information()");
                    return;
                }

                NET_ACCESS_CARD_INFO[] stOutParam1;
                NET_EM_FAILCODE[] stOutParam2;
                bool result = NETClient.GetOperateAccessCardService(m_LoginID, cardids, out stOutParam1, out stOutParam2, 5000);
                if (!result)
                {
                    MessageBox.Show("Get card info failed:" + NETClient.GetLastError());
                }
                else
                {
                    m_stuCardInfos = stOutParam1;
                    MessageBox.Show("Get card info success!");
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
        }

        private void button_AddCardInfo_Click(object sender, EventArgs e)
        {
            if (m_EigenLoginID == IntPtr.Zero)
            {
                MessageBox.Show("Please login first!");
                return;
            }
            else if (m_stuCardInfos == null)
            {
                MessageBox.Show("Not get card info!()");
                return;
            }

            try
            {
                bool result = false;
                NET_EM_FAILCODE[] stuOutErrArray = new NET_EM_FAILCODE[m_stuCardInfos.Length];
                result = NETClient.InsertOperateAccessCardService(m_EigenLoginID, m_stuCardInfos, out stuOutErrArray, 5000);
                if (!result)
                {
                    for (int i = 0; i < stuOutErrArray.Length; i++)
                    {
                        MessageBox.Show(GetFailCodeMsg(stuOutErrArray[i].emCode));
                    }
                }
                else
                {
                    MessageBox.Show("Add card info success()");
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
        }

        private void button_AddUserInfo_Click(object sender, EventArgs e)
        {
            if (m_EigenLoginID == IntPtr.Zero)
            {
                MessageBox.Show("Please login first!");
                return;
            }

            try
            {
                bool result = false;
                NET_ACCESS_USER_INFO[] stuInArray = new NET_ACCESS_USER_INFO[1] { m_stuUserInfo };
                NET_EM_FAILCODE[] stuOutErrArray = new NET_EM_FAILCODE[1];
                result = NETClient.InsertOperateAccessUserService(m_EigenLoginID, stuInArray, out stuOutErrArray, 5000);
                if (!result)
                {
                    for (int i = 0; i < stuOutErrArray.Length; i++)
                    {
                        MessageBox.Show(GetFailCodeMsg(stuOutErrArray[i].emCode));
                    }
                }
                else
                {
                    MessageBox.Show("Add user info success()");
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
        }

        private void UpdateButtonGroup(bool isDisable)
        {
            checkBox1.BackColor = (isDisable ? System.Drawing.Color.Green : System.Drawing.Color.Red);
            btn_Add.Enabled = !isDisable;
            btn_Delete.Enabled = !isDisable;
            button_GetUserInfo.Enabled = !isDisable;
            button_GetEigen.Enabled = !isDisable;
            button_GetCardInfo.Enabled = !isDisable;
            button_LogInEigenDevice.Enabled = !isDisable;
            button_AddUserInfo.Enabled = !isDisable;
            button_AddEigen.Enabled = !isDisable;
            button_AddCardInfo.Enabled = !isDisable;
        }

        private void CheckBox1_CheckedChanged(object sender, EventArgs e)
        { 
            bool isChecked = checkBox1.Checked;
            bool isOperateSuccess = false;
            NET_CFG_LEASE_RULES_INFO stRulesInfo = new NET_CFG_LEASE_RULES_INFO();
            stRulesInfo.dwSize = (uint)Marshal.SizeOf(typeof(NET_CFG_LEASE_RULES_INFO));
            object obj = (object)stRulesInfo;
            try
            {
                isOperateSuccess = NETClient.GetOperateConfig(m_LoginID, EM_CFG_OPERATE_TYPE.LEASE_RULES, -1, ref obj, typeof(NET_CFG_LEASE_RULES_INFO), 3000);
                if (isOperateSuccess)
                {
                    NET_CFG_LEASE_RULES_INFO stRetRulesInfo = (NET_CFG_LEASE_RULES_INFO)obj;
                    Console.WriteLine(stRetRulesInfo.bEnable);
                    stRetRulesInfo.bEnable = (isChecked ? true : false);
                    isOperateSuccess = NETClient.SetOperateConfig(m_LoginID, EM_CFG_OPERATE_TYPE.LEASE_RULES, -1, stRetRulesInfo, typeof(NET_CFG_LEASE_RULES_INFO), 3000);
                    if (!isOperateSuccess)
                    {
                        MessageBox.Show("Set config error():\n" + NETClient.GetLastError());
                    }
                }
                else
                {
                    MessageBox.Show("Get config error():\n" + NETClient.GetLastError());
                }
            }
            catch (Exception exp)
            {
                isOperateSuccess = false;
                MessageBox.Show(exp.Message);
                return;
            }

            if (!isOperateSuccess)
            {
                return;
            }

            UpdateButtonGroup(isChecked);
        }
    }
}
