using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Windows.Forms;
using NetSDKCS;
using System.Runtime.InteropServices;

namespace AccessDemo2s
{
    public partial class DeviceInfo : Form
    {
        private IntPtr loginID_ = IntPtr.Zero;
        private IntPtr loginDeviceID_ = IntPtr.Zero;
        private IntPtr findUserID_ = IntPtr.Zero;
        private bool isSingle_; //
        private bool isLocalDevice_;
        private List<NET_ACCESS_USER_INFO> usersInfoList_ = new List<NET_ACCESS_USER_INFO>();
        private int queryNum_ = 10;
        private Dictionary<string, IntPtr> userIDtoData_ = new Dictionary<string, IntPtr>(); //ID
        private Dictionary<string, int> userIDtoNum_ = new Dictionary<string, int>(); //ID

        public DeviceInfo(IntPtr loginID, bool isSingle, bool isLocalDevice)
        {
            InitializeComponent();
            loginID_ = loginID;
            isSingle_ = isSingle;
            isLocalDevice_ = isLocalDevice;
            if (isLocalDevice_)
            {
                groupBox1.Enabled = false; //
            }

            if (isSingle_)
            {
                checkedListBox1.Enabled = false;
            }
            else
            {
                comboBox1.Enabled = false;
            }

            GetAllUser();
            GetAllFingerprintInfo();
        }

        public void GetAllFingerprintInfo()
        {
            int cnt = usersInfoList_.Count;
            dataGridView1.Rows.Clear();
            for (int idx = 0; idx < cnt; ++idx)
            {
                NET_IN_ACCESS_FINGERPRINT_SERVICE_GET stuFingerPrintGetIn = new NET_IN_ACCESS_FINGERPRINT_SERVICE_GET();
                stuFingerPrintGetIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_ACCESS_FINGERPRINT_SERVICE_GET));
                stuFingerPrintGetIn.szUserID = usersInfoList_[idx].szUserID;

                NET_OUT_ACCESS_FINGERPRINT_SERVICE_GET stuFingerPrintGetOut = new NET_OUT_ACCESS_FINGERPRINT_SERVICE_GET();
                stuFingerPrintGetOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_ACCESS_FINGERPRINT_SERVICE_GET));
                stuFingerPrintGetOut.nMaxFingerDataLength = 10000;
                stuFingerPrintGetOut.pbyFingerData = IntPtr.Zero;
                stuFingerPrintGetOut.pbyFingerData = Marshal.AllocHGlobal(10000);

                IntPtr pstInParam = IntPtr.Zero;
                pstInParam = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_IN_ACCESS_FINGERPRINT_SERVICE_GET)));
                Marshal.StructureToPtr(stuFingerPrintGetIn, pstInParam, true);

                IntPtr pstOutParam = IntPtr.Zero;
                pstOutParam = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_OUT_ACCESS_FINGERPRINT_SERVICE_GET)));
                Marshal.StructureToPtr(stuFingerPrintGetOut, pstOutParam, true);

                bool result = NETClient.OperateAccessFingerprintService(loginID_, EM_ACCESS_CTL_FINGERPRINT_SERVICE.GET, pstInParam, pstOutParam, 5000);
                NET_OUT_ACCESS_FINGERPRINT_SERVICE_GET fingerprintOutInfo = (NET_OUT_ACCESS_FINGERPRINT_SERVICE_GET)Marshal.PtrToStructure(pstOutParam, typeof(NET_OUT_ACCESS_FINGERPRINT_SERVICE_GET));
                userIDtoData_[stuFingerPrintGetIn.szUserID] = fingerprintOutInfo.pbyFingerData;
                userIDtoNum_[stuFingerPrintGetIn.szUserID] = fingerprintOutInfo.nRetFingerPrintCount;

                NET_ACCESS_FINGERPRINT_INFO stuFingerprintInfo = new NET_ACCESS_FINGERPRINT_INFO();
                stuFingerprintInfo.szUserID = usersInfoList_[idx].szUserID;
                stuFingerprintInfo.nPacketLen = fingerprintOutInfo.nSinglePacketLength;
                stuFingerprintInfo.nPacketNum = fingerprintOutInfo.nRetFingerPrintCount;
                stuFingerprintInfo.szFingerPrintInfo = fingerprintOutInfo.pbyFingerData;
                stuFingerprintInfo.nDuressIndex = fingerprintOutInfo.nDuressIndex;
                IntPtr pFingerPrintInfo = IntPtr.Zero;
                pFingerPrintInfo = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_ACCESS_FINGERPRINT_INFO)));
                Marshal.StructureToPtr(stuFingerprintInfo, pFingerPrintInfo, true);
                userIDtoData_[stuFingerPrintGetIn.szUserID] = pFingerPrintInfo;

                for (int i = 0; i < fingerprintOutInfo.nRetFingerPrintCount; i++)
                {
                    DataGridViewRow row = new DataGridViewRow();

                    DataGridViewTextBoxCell cell1 = new DataGridViewTextBoxCell();
                    cell1.Value = usersInfoList_[idx].szUserID;
                    row.Cells.Add(cell1);

                    DataGridViewTextBoxCell cell2 = new DataGridViewTextBoxCell();
                    cell2.Value = (i + 1).ToString();
                    row.Cells.Add(cell2);

                    DataGridViewTextBoxCell cell3 = new DataGridViewTextBoxCell();
                    if (fingerprintOutInfo.nDuressIndex == (i + 1))
                    {
                        cell3.Value = "Duress()";
                    }
                    else
                    {
                        cell3.Value = "Normal()";
                    }
                    row.Cells.Add(cell3);

                    dataGridView1.Rows.Add(row);
                }

                if (isSingle_)
                {
                    comboBox1.Items.Add(usersInfoList_[idx].szUserID);
                }
                else
                {
                    checkedListBox1.Items.Add(usersInfoList_[idx].szUserID);
                }
            }
        }

        private void GetAllUser()
        {
            NET_IN_USERINFO_START_FIND stuStartIn = new NET_IN_USERINFO_START_FIND();
            stuStartIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_USERINFO_START_FIND));

            NET_OUT_USERINFO_START_FIND stuStartOut = new NET_OUT_USERINFO_START_FIND();
            stuStartOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_USERINFO_START_FIND));
            stuStartOut.nTotalCount = 0;
            stuStartOut.nCapNum = 50;
            findUserID_ = NETClient.StartFindUserInfo(loginID_, ref stuStartIn, ref stuStartOut, 5000);
            if (IntPtr.Zero == findUserID_)
            {
                MessageBox.Show(NETClient.GetLastError());
                return;
            }

            usersInfoList_.Clear();

            NET_IN_USERINFO_DO_FIND stuFindIn = new NET_IN_USERINFO_DO_FIND();
            stuFindIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_USERINFO_DO_FIND));
            stuFindIn.nCount = queryNum_;

            NET_OUT_USERINFO_DO_FIND stuFindOut = new NET_OUT_USERINFO_DO_FIND();
            stuFindOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_USERINFO_DO_FIND));
            stuFindOut.nMaxNum = queryNum_;

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

                bool result = NETClient.DoFindUserInfo(findUserID_, ref stuFindIn, ref stuFindOut, 5000);
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
                        usersInfoList_.Add(userinfo);
                    }
                }
            }

            NETClient.StopFindUserInfo(findUserID_);
        }

        private void Btn_Login_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero == loginDeviceID_)
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
                loginDeviceID_ = NETClient.LoginWithHighLevelSecurity(ip_textBox.Text.Trim(), port, user_textBox.Text.Trim(), pwd_textBox.Text.Trim(), EM_LOGIN_SPAC_CAP_TYPE.TCP, IntPtr.Zero, ref deviceInfo);
                if (IntPtr.Zero == loginDeviceID_)
                {
                    MessageBox.Show(this, NETClient.GetLastError());
                    return;
                }
                else
                {
                    btn_Login.Text = "Logout()";
                }
            }
            else
            {
                bool result = NETClient.Logout(loginDeviceID_);
                if (!result)
                {
                    MessageBox.Show(this, NETClient.GetLastError());
                }
                else
                {
                    loginDeviceID_ = IntPtr.Zero;
                    btn_Login.Text = "Login()";
                }
            }
        }

        private void BtnDistribute_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero == loginDeviceID_ && !isLocalDevice_)
            {
                MessageBox.Show("Please login first()");
                return;
            } 
            else if (userIDtoNum_.Count <= 0)
            {
                MessageBox.Show("No user information()");
                return;
            }

            if (isSingle_)
            {
                var item = comboBox1.SelectedItem;
                NET_IN_ACCESS_FINGERPRINT_SERVICE_INSERT stuFingerPrintInsertIn = new NET_IN_ACCESS_FINGERPRINT_SERVICE_INSERT();
                stuFingerPrintInsertIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_ACCESS_FINGERPRINT_SERVICE_INSERT));
                stuFingerPrintInsertIn.nFpNum = 1;
                stuFingerPrintInsertIn.pFingerPrintInfo = userIDtoData_[item.ToString()];

                IntPtr pstInParam = IntPtr.Zero;
                pstInParam = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_IN_ACCESS_FINGERPRINT_SERVICE_INSERT)));
                Marshal.StructureToPtr(stuFingerPrintInsertIn, pstInParam, true);

                NET_OUT_ACCESS_FINGERPRINT_SERVICE_INSERT stuFingerPrintInsertOut = new NET_OUT_ACCESS_FINGERPRINT_SERVICE_INSERT();
                stuFingerPrintInsertOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_ACCESS_FINGERPRINT_SERVICE_INSERT));

                NET_EM_FAILCODE stuFailCode = new NET_EM_FAILCODE();
                IntPtr pFailCode = IntPtr.Zero;
                pFailCode = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_EM_FAILCODE)) * userIDtoNum_[item.ToString()]);
                Marshal.StructureToPtr(stuFailCode, pFailCode, true);
                stuFingerPrintInsertOut.pFailCode = pFailCode;
                stuFingerPrintInsertOut.nMaxRetNum = userIDtoNum_[item.ToString()];

                IntPtr pstOutParam = IntPtr.Zero;
                pstOutParam = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_OUT_ACCESS_FINGERPRINT_SERVICE_INSERT)));
                Marshal.StructureToPtr(stuFingerPrintInsertOut, pstOutParam, true);
                
                bool result = NETClient.OperateAccessFingerprintService(isLocalDevice_ ? loginID_ : loginDeviceID_, EM_ACCESS_CTL_FINGERPRINT_SERVICE.INSERT, pstInParam, pstOutParam, 5000);
                if (result)
                {
                    MessageBox.Show("Distribute succeed()\n" + NETClient.GetLastError());
                }
                else
                {
                    MessageBox.Show("Distribute failed()\n" + NETClient.GetLastError());

                    StringBuilder stringBuilder = new StringBuilder($"The number of error details is {faceinfo.stuDetail.nRetExtraInfoNum}\nThe error details are: \n");
                    for (int i = 0; i < faceinfo.stuDetail.nRetExtraInfoNum; ++i)
                    {
                        char[] szErrDetailItem = faceinfo.stuDetail.szExtraInfo.ToCharArray(i * 256, 256);
                        stringBuilder.AppendLine(szErrDetailItem.ToString());
                    }
                    MessageBox.Show(stringBuilder.ToString());
                }
            }
            else
            {
                var items = checkedListBox1.CheckedItems;
                foreach (var item in items)
                {
                    NET_IN_ACCESS_FINGERPRINT_SERVICE_INSERT stuFingerPrintInsertIn = new NET_IN_ACCESS_FINGERPRINT_SERVICE_INSERT();
                    stuFingerPrintInsertIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_ACCESS_FINGERPRINT_SERVICE_INSERT));
                    stuFingerPrintInsertIn.nFpNum = 1;
                    stuFingerPrintInsertIn.pFingerPrintInfo = userIDtoData_[item.ToString()];

                    IntPtr pstInParam = IntPtr.Zero;
                    pstInParam = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_IN_ACCESS_FINGERPRINT_SERVICE_INSERT)));
                    Marshal.StructureToPtr(stuFingerPrintInsertIn, pstInParam, true);

                    NET_OUT_ACCESS_FINGERPRINT_SERVICE_INSERT stuFingerPrintInsertOut = new NET_OUT_ACCESS_FINGERPRINT_SERVICE_INSERT();
                    stuFingerPrintInsertOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_ACCESS_FINGERPRINT_SERVICE_INSERT));

                    NET_EM_FAILCODE stuFailCode = new NET_EM_FAILCODE();
                    IntPtr pFailCode = IntPtr.Zero;
                    pFailCode = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_EM_FAILCODE)) * userIDtoNum_[item.ToString()]);
                    Marshal.StructureToPtr(stuFailCode, pFailCode, true);
                    stuFingerPrintInsertOut.pFailCode = pFailCode;
                    stuFingerPrintInsertOut.nMaxRetNum = userIDtoNum_[item.ToString()];

                    IntPtr pstOutParam = IntPtr.Zero;
                    pstOutParam = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_OUT_ACCESS_FINGERPRINT_SERVICE_INSERT)));
                    Marshal.StructureToPtr(stuFingerPrintInsertOut, pstOutParam, true);
                    bool result = NETClient.OperateAccessFingerprintService(isLocalDevice_ ? loginID_ : loginDeviceID_, EM_ACCESS_CTL_FINGERPRINT_SERVICE.INSERT, pstInParam, pstOutParam, 5000);
                    if (result)
                    {
                        MessageBox.Show("Distribute succeed()\n" + NETClient.GetLastError());
                    }
                    else
                    {
                        MessageBox.Show("Distribute failed()\n" + NETClient.GetLastError());
                        StringBuilder stringBuilder = new StringBuilder($"The number of error details is {faceinfo.stuDetail.nRetExtraInfoNum}\nThe error details are: \n");
                        for (int i = 0; i < faceinfo.stuDetail.nRetExtraInfoNum; ++i)
                        {
                            char[] szErrDetailItem = faceinfo.stuDetail.szExtraInfo.ToCharArray(i * 256, 256);
                            stringBuilder.AppendLine(szErrDetailItem.ToString());
                        }
                        MessageBox.Show(stringBuilder.ToString());
                    }
                }
            }
        }
    }
}
