using NetSDKCS;
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AccessDemo2s
{
    public partial class UserInfoForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;
        private EM_OperateType m_OperateType = EM_OperateType.Add;
        private NET_ACCESS_USER_INFO m_UserInfo = new NET_ACCESS_USER_INFO();
        private int m_Channel = 0;

        private byte[] m_ImageData;
        private List<NET_ACCESS_CARD_INFO> cardInfoList = new List<NET_ACCESS_CARD_INFO>();
        private NET_OUT_ACCESS_FINGERPRINT_SERVICE_GET m_FingerprintInfo = new NET_OUT_ACCESS_FINGERPRINT_SERVICE_GET();
        private int ListViewCount = 50;

        private static string m_Encrypt32 = "netsdktestCLIENT_EncryptString12";

        private IntPtr m_attachHandler = IntPtr.Zero;
        private fAnalyzerDataCallBack m_analyzerDataCallBack = null;

        public UserInfoForm()
        {
            InitializeComponent();
        }

        public UserInfoForm(IntPtr loginid, EM_OperateType type, NET_ACCESS_USER_INFO info, int channel)
        {
            InitializeComponent();
            m_LoginID = loginid;
            m_OperateType = type;
            m_UserInfo = info;
            m_Channel = channel;
        }

        ~UserInfoForm()
        {
            if (IntPtr.Zero != m_attachHandler)
            {
                NETClient.StopLoadPic(m_attachHandler);
            }
        }

        private void UserInfoForm_Load(object sender, EventArgs e)
        {
            if (m_OperateType == EM_OperateType.Modify)
            {
                btn_Confirm.Text = "Modify()";
                txt_UserID.Text = m_UserInfo.szUserID;
                txt_Name.Text = m_UserInfo.szName;
                txt_Pwd.Text = m_UserInfo.szPsw;
                cmb_Authority.SelectedIndex = (int)m_UserInfo.emAuthority + 1;
                if (m_UserInfo.nTimeSectionNum >= 1)
                {
                    txt_TimeSection.Text = m_UserInfo.nTimeSectionNo[0].ToString();
                }
                else
                {
                    txt_TimeSection.Text = "255";
                }
                if (m_UserInfo.nSpecialDaysScheduleNum >= 1)
                {
                    txt_SpecialDays.Text = m_UserInfo.nSpecialDaysSchedule[0].ToString();
                }
                else
                {
                    txt_SpecialDays.Text = "255";
                }
                cmb_UserType.SelectedIndex = (int)m_UserInfo.emUserType + 1;
                txt_UserTime.Text = m_UserInfo.nUserTime.ToString();
                chb_FirstEnter.Checked = m_UserInfo.bFirstEnter;
                dateTimePicker_Start.Value = m_UserInfo.stuValidBeginTime.ToDateTime();
                dateTimePicker_End.Value = m_UserInfo.stuValidEndTime.ToDateTime();

                txt_UserID.ReadOnly = true;

                btn_OpenPic.Enabled = true;
                btn_GetFace.Enabled = true;
                btn_AddFace.Enabled = true;
                btn_ModifyFace.Enabled = true;
                btn_DeleteFace.Enabled = true;

                btn_GetCard.Enabled = true;
                btn_AddCard.Enabled = true;
                btn_ModifyCard.Enabled = true;
                btn_DeleteCard.Enabled = true;

                btn_GetFingerprint.Enabled = true;
                btn_AddFingerprint.Enabled = true;
                btn_ModifyFingerprint.Enabled = true;
                btn_DeleteFingerprint.Enabled = true;

                GetFaceInfo();
                GetAllCardInfo();
                GetAllFingerprintInfo();
            }
            else
            {
                btn_Confirm.Text = "Add()";
                cmb_Authority.SelectedIndex = 1;
                txt_TimeSection.Text = "255";
                txt_SpecialDays.Text = "255";
                cmb_UserType.SelectedIndex = 1;
                dateTimePicker_Start.Value = DateTime.Now;
                dateTimePicker_End.Value = DateTime.Now.AddYears(10);

                txt_UserID.ReadOnly = false;

                btn_OpenPic.Enabled = false;
                btn_GetFace.Enabled = false;
                btn_AddFace.Enabled = false;
                btn_ModifyFace.Enabled = false;
                btn_DeleteFace.Enabled = false;

                btn_GetCard.Enabled = false;
                btn_AddCard.Enabled = false;
                btn_ModifyCard.Enabled = false;
                btn_DeleteCard.Enabled = false;

                btn_GetFingerprint.Enabled = false;
                btn_AddFingerprint.Enabled = false;
                btn_ModifyFingerprint.Enabled = false;
                btn_DeleteFingerprint.Enabled = false;
            }
        }

        private void btn_Confirm_Click(object sender, EventArgs e)
        {
            try
            {
                int temp;
                m_UserInfo.szUserID = txt_UserID.Text.Trim();
                m_UserInfo.szName = txt_Name.Text.Trim();
                m_UserInfo.szPsw = txt_Pwd.Text.Trim();
                m_UserInfo.emAuthority = (EM_ATTENDANCE_AUTHORITY)(cmb_Authority.SelectedIndex - 1);
                if (int.TryParse(txt_TimeSection.Text, out temp))
                {
                    m_UserInfo.nTimeSectionNum = 1;
                    m_UserInfo.nTimeSectionNo = new int[32];
                    m_UserInfo.nTimeSectionNo[0] = int.Parse(txt_TimeSection.Text);
                }
                if (int.TryParse(txt_SpecialDays.Text, out temp))
                {
                    m_UserInfo.nSpecialDaysScheduleNum = 1;
                    m_UserInfo.nSpecialDaysSchedule = new int[128];
                    m_UserInfo.nSpecialDaysSchedule[0] = int.Parse(txt_SpecialDays.Text);
                }
                m_UserInfo.emUserType = (EM_USER_TYPE)(cmb_UserType.SelectedIndex - 1);
                m_UserInfo.nUserTime = int.Parse(txt_UserTime.Text);
                m_UserInfo.bFirstEnter = chb_FirstEnter.Checked;

                if (chb_FirstEnter.Checked)
                {
                    m_UserInfo.bFirstEnter = true;
                    m_UserInfo.nFirstEnterDoorsNum = 1;
                    m_UserInfo.nFirstEnterDoors = new int[32];
                    m_UserInfo.nFirstEnterDoors[0] = -1;
                }
                else
                {
                    m_UserInfo.bFirstEnter = false;
                    m_UserInfo.nFirstEnterDoorsNum = 0;
                }

                m_UserInfo.stuValidBeginTime = NET_TIME.FromDateTime(dateTimePicker_Start.Value);
                m_UserInfo.stuValidEndTime = NET_TIME.FromDateTime(dateTimePicker_End.Value);
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                return;
            }


            bool result = false;
            NET_ACCESS_USER_INFO[] stuInArray = new NET_ACCESS_USER_INFO[1] { m_UserInfo };
            NET_EM_FAILCODE[] stuOutErrArray = new NET_EM_FAILCODE[1];
            result = NETClient.InsertOperateAccessUserService(m_LoginID, stuInArray, out stuOutErrArray, 5000);


            if (!result)
            {
                for (int i = 0; i < stuOutErrArray.Length; i++)
                {
                    MessageBox.Show(GetFailCodeMsg(stuOutErrArray[i].emCode));
                }
            }

            Close();
        }

        private void btn_Cancel_Click(object sender, EventArgs e)
        {
            Close();
        }

        private void btn_OpenPic_Click(object sender, EventArgs e)
        {
            OpenFileDialog openFileDialog = new OpenFileDialog();
            openFileDialog.Filter = "JPG|*.jpg";
            var ret = openFileDialog.ShowDialog();
            if (ret == DialogResult.OK)
            {
                try
                {
                    string path = openFileDialog.FileName;
                    m_ImageData = File.ReadAllBytes(path);
                    using (MemoryStream stream = new MemoryStream(m_ImageData))
                    {
                        Image image = Image.FromStream(stream);
                        pictureBox_face.Image = image;
                        pictureBox_face.Refresh();
                    }
                    openFileDialog.Dispose();
                }
                catch (Exception ex)
                {
                    MessageBox.Show(ex.Message);
                    openFileDialog.Dispose();
                }
            }
            openFileDialog.Dispose();
        }

        private void btn_GetFace_Click(object sender, EventArgs e)
        {
            GetFaceInfo();
        }

        private void btn_AddFace_Click(object sender, EventArgs e)
        {
            if (m_ImageData == null || m_ImageData.Length <= 0)
            {
                return;
            }

            NET_IN_ACCESS_FACE_SERVICE_INSERT stuFaceInsertIn = new NET_IN_ACCESS_FACE_SERVICE_INSERT();
            stuFaceInsertIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_ACCESS_FACE_SERVICE_INSERT));
            stuFaceInsertIn.nFaceInfoNum = 1;
            stuFaceInsertIn.pFaceInfo = IntPtr.Zero;
            stuFaceInsertIn.pFaceInfo = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_ACCESS_FACE_INFO)));

            NET_ACCESS_FACE_INFO stuFaceInfo = new NET_ACCESS_FACE_INFO();
            stuFaceInfo.szUserID = m_UserInfo.szUserID;
            stuFaceInfo.nFacePhoto = 1;
            stuFaceInfo.nInFacePhotoLen = new int[5];
            stuFaceInfo.nOutFacePhotoLen = new int[5];
            stuFaceInfo.nInFacePhotoLen[0] = stuFaceInfo.nOutFacePhotoLen[0] = m_ImageData.Length;
            stuFaceInfo.pFacePhoto = new IntPtr[5];
            stuFaceInfo.pFacePhoto[0] = Marshal.AllocHGlobal(stuFaceInfo.nInFacePhotoLen[0]);
            Marshal.Copy(m_ImageData, 0, stuFaceInfo.pFacePhoto[0], stuFaceInfo.nInFacePhotoLen[0]);

            Marshal.StructureToPtr(stuFaceInfo, stuFaceInsertIn.pFaceInfo, true);

            NET_OUT_ACCESS_FACE_SERVICE_INSERT stuFaceInsertOut = new NET_OUT_ACCESS_FACE_SERVICE_INSERT();
            stuFaceInsertOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_ACCESS_FACE_SERVICE_INSERT));
            stuFaceInsertOut.nMaxRetNum = 1;
            stuFaceInsertOut.pFailCode = IntPtr.Zero;
            stuFaceInsertOut.pFailCode = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_EM_FAILCODE)));

            NET_EM_FAILCODE stuFailCodeR = new NET_EM_FAILCODE();
            Marshal.StructureToPtr(stuFailCodeR, stuFaceInsertOut.pFailCode, true);

            IntPtr pstInParam = IntPtr.Zero;
            pstInParam = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_IN_ACCESS_FACE_SERVICE_INSERT)));
            Marshal.StructureToPtr(stuFaceInsertIn, pstInParam, true);

            IntPtr pstOutParam = IntPtr.Zero;
            pstOutParam = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_OUT_ACCESS_FACE_SERVICE_INSERT)));
            Marshal.StructureToPtr(stuFaceInsertOut, pstOutParam, true);

            try
            {
                bool result = NETClient.OperateAccessFaceService(m_LoginID, EM_NET_ACCESS_CTL_FACE_SERVICE.INSERT, pstInParam, pstOutParam, 5000);
                var faceinfo = (NET_OUT_ACCESS_FACE_SERVICE_INSERT)Marshal.PtrToStructure(pstOutParam, typeof(NET_OUT_ACCESS_FACE_SERVICE_INSERT));
                if (!result)
                {
                    var failcode = (NET_EM_FAILCODE)Marshal.PtrToStructure(faceinfo.pFailCode, typeof(NET_EM_FAILCODE));
                    if (failcode.emCode == EM_FAILCODE.NOERROR)
                    {
                        MessageBox.Show(NETClient.GetLastError());
                    }
                    else
                    {
                        MessageBox.Show(GetFailCodeMsg(failcode.emCode));
                    }

                    StringBuilder stringBuilder = new StringBuilder($"The number of error details is {faceinfo.stuDetail.nRetExtraInfoNum}\nThe error details are: \n");
                    IntPtr pstrBuffer = Marshal.StringToCoTaskMemAuto(faceinfo.stuDetail.szExtraInfo);
                    
                    for (int i = 0; i < faceinfo.stuDetail.nRetExtraInfoNum; ++i)
                    {
                        string strItem = Marshal.PtrToStringAuto(pstrBuffer + i * 256, 256);
                        stringBuilder.AppendLine(strItem);
                    }
                    MessageBox.Show(stringBuilder.ToString());
                    Marshal.FreeHGlobal(pstrBuffer);

                    return;
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            finally
            {
                Marshal.FreeHGlobal(stuFaceInsertIn.pFaceInfo);
                Marshal.FreeHGlobal(stuFaceInfo.pFacePhoto[0]);
                Marshal.FreeHGlobal(stuFaceInsertOut.pFailCode);
                Marshal.FreeHGlobal(pstInParam);
                Marshal.FreeHGlobal(pstOutParam);
            }

            GetFaceInfo();
        }

        private void btn_ModifyFace_Click(object sender, EventArgs e)
        {
            if (m_ImageData == null || m_ImageData.Length <= 0)
            {
                return;
            }
            
            NET_IN_ACCESS_FACE_SERVICE_UPDATE stuFaceUpdateIn = new NET_IN_ACCESS_FACE_SERVICE_UPDATE();
            stuFaceUpdateIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_ACCESS_FACE_SERVICE_UPDATE));
            stuFaceUpdateIn.nFaceInfoNum = 1;
            stuFaceUpdateIn.pFaceInfo = IntPtr.Zero;
            stuFaceUpdateIn.pFaceInfo = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_ACCESS_FACE_INFO)));

            NET_ACCESS_FACE_INFO stuFaceInfo = new NET_ACCESS_FACE_INFO();
            stuFaceInfo.szUserID = m_UserInfo.szUserID;
            stuFaceInfo.nFacePhoto = 1;
            stuFaceInfo.nInFacePhotoLen = new int[5];
            stuFaceInfo.nOutFacePhotoLen = new int[5];
            stuFaceInfo.nInFacePhotoLen[0] = stuFaceInfo.nOutFacePhotoLen[0] = m_ImageData.Length;
            stuFaceInfo.pFacePhoto = new IntPtr[5];
            stuFaceInfo.pFacePhoto[0] = Marshal.AllocHGlobal(stuFaceInfo.nInFacePhotoLen[0]);
            Marshal.Copy(m_ImageData, 0, stuFaceInfo.pFacePhoto[0], stuFaceInfo.nInFacePhotoLen[0]);

            Marshal.StructureToPtr(stuFaceInfo, stuFaceUpdateIn.pFaceInfo, true);

            NET_OUT_ACCESS_FACE_SERVICE_UPDATE stuFaceUpdateOut = new NET_OUT_ACCESS_FACE_SERVICE_UPDATE(); //{ sizeof(stuFaceUpdateOut) };
            stuFaceUpdateOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_ACCESS_FACE_SERVICE_UPDATE));
            stuFaceUpdateOut.nMaxRetNum = 1;
            stuFaceUpdateOut.pFailCode = IntPtr.Zero;
            stuFaceUpdateOut.pFailCode = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_EM_FAILCODE)));

            NET_EM_FAILCODE stuFailCodeR = new NET_EM_FAILCODE();
            Marshal.StructureToPtr(stuFailCodeR, stuFaceUpdateOut.pFailCode, true);

            IntPtr pstInParam = IntPtr.Zero;
            pstInParam = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_IN_ACCESS_FACE_SERVICE_UPDATE)));
            Marshal.StructureToPtr(stuFaceUpdateIn, pstInParam, true);

            IntPtr pstOutParam = IntPtr.Zero;
            pstOutParam = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_OUT_ACCESS_FACE_SERVICE_UPDATE)));
            Marshal.StructureToPtr(stuFaceUpdateOut, pstOutParam, true);

            try
            {
                bool result = NETClient.OperateAccessFaceService(m_LoginID, EM_NET_ACCESS_CTL_FACE_SERVICE.UPDATE, pstInParam, pstOutParam, 5000);
                var faceinfo = (NET_OUT_ACCESS_FACE_SERVICE_UPDATE)Marshal.PtrToStructure(pstOutParam, typeof(NET_OUT_ACCESS_FACE_SERVICE_UPDATE));
                if (!result)
                {
                    var failcode = (NET_EM_FAILCODE)Marshal.PtrToStructure(faceinfo.pFailCode, typeof(NET_EM_FAILCODE));
                    if (failcode.emCode == EM_FAILCODE.NOERROR)
                    {
                        MessageBox.Show(NETClient.GetLastError());
                    }
                    else
                    {
                        MessageBox.Show(GetFailCodeMsg(failcode.emCode));
                    }

                    StringBuilder stringBuilder = new StringBuilder($"The number of error details is {faceinfo.stuDetail.nRetExtraInfoNum}\nThe error details are: \n");
                    for (int i = 0; i < faceinfo.stuDetail.nRetExtraInfoNum; ++i)
                    {
                        char[] szErrDetailItem = faceinfo.stuDetail.szExtraInfo.ToCharArray(i * 256, 256);
                        stringBuilder.AppendLine(szErrDetailItem.ToString());
                    }
                    MessageBox.Show(stringBuilder.ToString());
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            finally
            {
                Marshal.FreeHGlobal(stuFaceUpdateIn.pFaceInfo);
                Marshal.FreeHGlobal(stuFaceInfo.pFacePhoto[0]);
                Marshal.FreeHGlobal(stuFaceUpdateOut.pFailCode);
                Marshal.FreeHGlobal(pstInParam);
                Marshal.FreeHGlobal(pstOutParam);
            }

            GetFaceInfo();
        }

        private void btn_DeleteFace_Click(object sender, EventArgs e)
        {
            NET_IN_ACCESS_FACE_SERVICE_REMOVE stuFaceRemoveIn = new NET_IN_ACCESS_FACE_SERVICE_REMOVE();
            stuFaceRemoveIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_ACCESS_FACE_SERVICE_REMOVE));
            stuFaceRemoveIn.nUserNum = 1;
            stuFaceRemoveIn.szUserID = new NET_IN_ACCESS_FACE_SERVICE_UserID[100];
            stuFaceRemoveIn.szUserID[0] = new NET_IN_ACCESS_FACE_SERVICE_UserID() { userID = m_UserInfo.szUserID };

            NET_OUT_ACCESS_FACE_SERVICE_REMOVE stuFaceRemoveOut = new NET_OUT_ACCESS_FACE_SERVICE_REMOVE();//{ sizeof(stuFaceROut) };
            stuFaceRemoveOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_ACCESS_FACE_SERVICE_REMOVE));
            stuFaceRemoveOut.nMaxRetNum = 1;
            stuFaceRemoveOut.pFailCode = IntPtr.Zero;
            stuFaceRemoveOut.pFailCode = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_EM_FAILCODE)));

            NET_EM_FAILCODE stuFailCodeR = new NET_EM_FAILCODE();
            Marshal.StructureToPtr(stuFailCodeR, stuFaceRemoveOut.pFailCode, true);

            IntPtr pstInParam = IntPtr.Zero;
            pstInParam = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_IN_ACCESS_FACE_SERVICE_REMOVE)));
            Marshal.StructureToPtr(stuFaceRemoveIn, pstInParam, true);

            IntPtr pstOutParam = IntPtr.Zero;
            pstOutParam = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_OUT_ACCESS_FACE_SERVICE_REMOVE)));
            Marshal.StructureToPtr(stuFaceRemoveOut, pstOutParam, true);

            try
            {
                bool result = NETClient.OperateAccessFaceService(m_LoginID, EM_NET_ACCESS_CTL_FACE_SERVICE.REMOVE, pstInParam, pstOutParam, 5000);
                var faceinfo = (NET_OUT_ACCESS_FACE_SERVICE_REMOVE)Marshal.PtrToStructure(pstOutParam, typeof(NET_OUT_ACCESS_FACE_SERVICE_REMOVE));
                if (!result)
                {
                    var failcode = (NET_EM_FAILCODE)Marshal.PtrToStructure(faceinfo.pFailCode, typeof(NET_EM_FAILCODE));
                    if (failcode.emCode == EM_FAILCODE.NOERROR)
                    {
                        MessageBox.Show(NETClient.GetLastError());
                    }
                    else
                    {
                        MessageBox.Show(GetFailCodeMsg(failcode.emCode));
                    }
                }
                else
                {
                    pictureBox_face.Image = null;
                    pictureBox_face.Refresh();
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            finally
            {
                Marshal.FreeHGlobal(stuFaceRemoveOut.pFailCode);
                Marshal.FreeHGlobal(pstInParam);
                Marshal.FreeHGlobal(pstOutParam);
            }
        }

        private void btn_GetFingerprint_Click(object sender, EventArgs e)
        {
            GetAllFingerprintInfo();
        }

        private void btn_AddFingerprint_Click(object sender, EventArgs e)
        {
            if (m_FingerprintInfo.nRetFingerPrintCount >= 3)
            {
                MessageBox.Show("The fingerprints are full!(ZW!)");
                return;
            }
            NET_ACCESS_FINGERPRINT_INFO fingerprint_info = new NET_ACCESS_FINGERPRINT_INFO();
            fingerprint_info.szUserID = m_UserInfo.szUserID;
            UserFingerprintInfoForm fingerprintInfo = new UserFingerprintInfoForm(m_LoginID, EM_OperateType.Add, fingerprint_info, m_FingerprintInfo.nRetFingerPrintCount + 1);
            var ret = fingerprintInfo.ShowDialog();
            fingerprintInfo.Dispose();

            GetAllFingerprintInfo();
        }

        private void btn_ModifyFingerprint_Click(object sender, EventArgs e)
        {
            if (listView_Fingerprint.SelectedItems.Count == 0)
            {
                MessageBox.Show("Please select one fringerprint!(ZW！)");
                return;
            }
            string str_fingerprint_No = listView_Fingerprint.SelectedItems[0].SubItems[0].Text;
            int fingerprint_Num;
            if (!int.TryParse(str_fingerprint_No, out fingerprint_Num))
            {
                MessageBox.Show("The select data is error!(！)");
                return;
            }

            NET_ACCESS_FINGERPRINT_INFO fingerprint_info = new NET_ACCESS_FINGERPRINT_INFO();
            fingerprint_info.szUserID = m_UserInfo.szUserID;
            fingerprint_info.nPacketLen = m_FingerprintInfo.nSinglePacketLength;
            fingerprint_info.nPacketNum = m_FingerprintInfo.nRetFingerPrintCount;
            fingerprint_info.nDuressIndex = m_FingerprintInfo.nDuressIndex;
            fingerprint_info.szFingerPrintInfo = m_FingerprintInfo.pbyFingerData;

            UserFingerprintInfoForm fingerprintInfo = new UserFingerprintInfoForm(m_LoginID, EM_OperateType.Modify, fingerprint_info, fingerprint_Num);
            var ret = fingerprintInfo.ShowDialog();
            fingerprintInfo.Dispose();

            GetAllFingerprintInfo();
        }

        private void btn_DeleteFingerprint_Click(object sender, EventArgs e)
        {
            bool result = false;
            NET_EM_FAILCODE[] stuOutErrArray;
            string[] userid = new string[] { m_UserInfo.szUserID };
            result = NETClient.RemoveOperateAccessFingerprintService(m_LoginID, userid, out stuOutErrArray, 3000);
            if (!result)
            {
                for (int i = 0; i < stuOutErrArray.Length; i++)
                {
                    MessageBox.Show(GetFailCodeMsg(stuOutErrArray[i].emCode));
                }
            }

            GetAllFingerprintInfo();
        }

        private void btn_GetCard_Click(object sender, EventArgs e)
        {
            GetAllCardInfo();
        }

        private void btn_AddCard_Click(object sender, EventArgs e)
        {
            if (cardInfoList.Count >= 5)
            {
                MessageBox.Show("The cards are full!(!)");
                return;
            }
            UserCardInfoForm userInfo = new UserCardInfoForm(m_LoginID, EM_OperateType.Add, new NET_ACCESS_CARD_INFO() { szUserID = m_UserInfo.szUserID});
            userInfo.ShowDialog();
            userInfo.Dispose();
            GetAllCardInfo();
        }

        private void btn_ModifyCard_Click(object sender, EventArgs e)
        {
            if (listView_Card.SelectedItems.Count == 0)
            {
                MessageBox.Show("Please select one card!(！)");
                return;
            }
            string card_No = listView_Card.SelectedItems[0].SubItems[2].Text;

            var infolist = cardInfoList.Where(a => a.szCardNo == card_No).ToList();
            if (infolist.Count != 1)
            {
                MessageBox.Show("The select data is error!(！)");
                return;
            }
            var select_card_info = infolist[0];

            UserCardInfoForm userInfo = new UserCardInfoForm(m_LoginID, EM_OperateType.Modify, select_card_info);
            userInfo.ShowDialog();
            userInfo.Dispose();
            GetAllCardInfo();
        }

        private void btn_DeleteCard_Click(object sender, EventArgs e)
        {
            if (listView_Card.SelectedItems.Count == 0)
            {
                MessageBox.Show("Please select one user!(！)");
                return;
            }
            string card_No = listView_Card.SelectedItems[0].SubItems[2].Text;

            var infolist = cardInfoList.Where(a => a.szCardNo == card_No).ToList();
            if (infolist.Count != 1)
            {
                MessageBox.Show("The select data is error!(！)");
                return;
            }
            var select_card_info = infolist[0];

            NET_EM_FAILCODE[] stuOutErrArray = new NET_EM_FAILCODE[1];
            string[] InCardid = new string[] { select_card_info.szCardNo };
            bool result = NETClient.RemoveOperateAccessCardService(m_LoginID, InCardid, out stuOutErrArray, 3000);
            if (!result)
            {
                for (int i = 0; i < stuOutErrArray.Length; i++)
                {
                    MessageBox.Show(GetFailCodeMsg(stuOutErrArray[i].emCode));
                }
            }
            GetAllCardInfo();
        }

        private void GetFaceInfo()
        {
            pictureBox_face.Image = null;
            pictureBox_face.Refresh();

            NET_IN_ACCESS_FACE_SERVICE_GET stuFaceGetIn = new NET_IN_ACCESS_FACE_SERVICE_GET();
            stuFaceGetIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_ACCESS_FACE_SERVICE_GET));
            stuFaceGetIn.nUserNum = 1;
            stuFaceGetIn.szUserID = new NET_IN_ACCESS_FACE_SERVICE_UserID[100];
            stuFaceGetIn.szUserID[0] = new NET_IN_ACCESS_FACE_SERVICE_UserID() { userID = m_UserInfo.szUserID };//m_UserInfo.szUserID;

            NET_OUT_ACCESS_FACE_SERVICE_GET stuFaceGetOut = new NET_OUT_ACCESS_FACE_SERVICE_GET();
            stuFaceGetOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_ACCESS_FACE_SERVICE_GET));
            stuFaceGetOut.nMaxRetNum = 1;
            stuFaceGetOut.pFaceInfo = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_ACCESS_FACE_INFO)));
            stuFaceGetOut.pFailCode = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_EM_FAILCODE)));

            NET_ACCESS_FACE_INFO stuFaceInfo = new NET_ACCESS_FACE_INFO();
            stuFaceInfo.nInFacePhotoLen = new int[5];
            stuFaceInfo.pFacePhoto = new IntPtr[5];
            for (int i = 0; i < 5; i++)
            {
                stuFaceInfo.nInFacePhotoLen[i] = 100 * 1024;
                IntPtr tempPtr = IntPtr.Zero;
                tempPtr = Marshal.AllocHGlobal(100 * 1024);
                for (int j = 0; j < 100 * 1024; j++)
                {
                    Marshal.WriteByte(tempPtr, j, 0);
                }
                stuFaceInfo.pFacePhoto[i] = tempPtr;
            }
            Marshal.StructureToPtr(stuFaceInfo, stuFaceGetOut.pFaceInfo, true);

            NET_EM_FAILCODE stuFailCode = new NET_EM_FAILCODE();
            Marshal.StructureToPtr(stuFailCode, stuFaceGetOut.pFailCode, true);
            IntPtr pstInParam = IntPtr.Zero;
            pstInParam = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_IN_ACCESS_FACE_SERVICE_GET)));
            Marshal.StructureToPtr(stuFaceGetIn, pstInParam, true);
            IntPtr pstOutParam = IntPtr.Zero;
            pstOutParam = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_OUT_ACCESS_FACE_SERVICE_GET)));
            Marshal.StructureToPtr(stuFaceGetOut, pstOutParam, true);
            try
            {
                bool result = NETClient.OperateAccessFaceService(m_LoginID, EM_NET_ACCESS_CTL_FACE_SERVICE.GET, pstInParam, pstOutParam, 5000);
                var get_face_service = (NET_OUT_ACCESS_FACE_SERVICE_GET)Marshal.PtrToStructure(pstOutParam, typeof(NET_OUT_ACCESS_FACE_SERVICE_GET));
                if (!result)
                {
                    stuFailCode = (NET_EM_FAILCODE)Marshal.PtrToStructure(get_face_service.pFailCode, typeof(NET_EM_FAILCODE));

                    if (stuFailCode.emCode == EM_FAILCODE.NOERROR)
                    {
                        MessageBox.Show(NETClient.GetLastError());
                    }
                    else if (stuFailCode.emCode != EM_FAILCODE.UNKNOWN)
                    {
                        MessageBox.Show(GetFailCodeMsg(stuFailCode.emCode));
                    }
                }
                else
                {
                    stuFaceInfo = (NET_ACCESS_FACE_INFO)Marshal.PtrToStructure(get_face_service.pFaceInfo, typeof(NET_ACCESS_FACE_INFO));
                    if (stuFaceInfo.nFacePhoto > 0)
                    {
                        m_ImageData = new byte[stuFaceInfo.nOutFacePhotoLen[0]];
                        Marshal.Copy(stuFaceInfo.pFacePhoto[0], m_ImageData, 0, stuFaceInfo.nOutFacePhotoLen[0]);
                        using (MemoryStream stream = new MemoryStream(m_ImageData))
                        {
                            Image image = Image.FromStream(stream);
                            pictureBox_face.Image = image;
                            pictureBox_face.Refresh();
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            finally
            {
                Marshal.FreeHGlobal(stuFaceGetOut.pFaceInfo);
                Marshal.FreeHGlobal(stuFaceGetOut.pFailCode);
                for (int i = 0; i < 5; i++)
                {
                    Marshal.FreeHGlobal(stuFaceInfo.pFacePhoto[i]);
                }
                Marshal.FreeHGlobal(pstInParam);
                Marshal.FreeHGlobal(pstOutParam);
            }
        }

        private void GetAllCardInfo()
        {
            cardInfoList.Clear();

            NET_IN_CARDINFO_START_FIND stuStartIn = new NET_IN_CARDINFO_START_FIND();
            stuStartIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_CARDINFO_START_FIND));
            stuStartIn.szUserID = m_UserInfo.szUserID;

            NET_OUT_CARDINFO_START_FIND stuStartOut = new NET_OUT_CARDINFO_START_FIND();
            stuStartOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_CARDINFO_START_FIND));
            stuStartOut.nTotalCount = 0;
            stuStartOut.nCapNum = 10;

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
                    stuFindOut.pstuInfo = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_ACCESS_CARD_INFO)) * stuFindOut.nMaxNum);

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
                                cardInfoList.Add(stuInfo);
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
                ShowCardInfoInListView();
            }
            else
            {
                MessageBox.Show(NETClient.GetLastError());
            }
        }

        private void ShowCardInfoInListView()
        {
            listView_Card.Items.Clear();
            if (cardInfoList.Count <= 0)
            {
                return;
            }

            int index = 0;
            foreach (var info in cardInfoList)
            {
                index++;

                var item = new ListViewItem();
                item.Text = index.ToString();

                switch (info.emType)
                {
                    case EM_ACCESSCTLCARD_TYPE.GENERAL:
                        item.SubItems.Add("General()");
                        break;
                    case EM_ACCESSCTLCARD_TYPE.VIP:
                        item.SubItems.Add("V/P(V/P)");
                        break;
                    case EM_ACCESSCTLCARD_TYPE.GUEST:
                        item.SubItems.Add("Guest()");
                        break;
                    case EM_ACCESSCTLCARD_TYPE.PATROL:
                        item.SubItems.Add("Patrol()");
                        break;
                    case EM_ACCESSCTLCARD_TYPE.BLACKLIST:
                        item.SubItems.Add("No permission List()");
                        break;
                    case EM_ACCESSCTLCARD_TYPE.CORCE:
                        item.SubItems.Add("Duress()");
                        break;
                    case EM_ACCESSCTLCARD_TYPE.POLLING:
                        item.SubItems.Add("Polling()");
                        break;
                    case EM_ACCESSCTLCARD_TYPE.MOTHERCARD:
                        item.SubItems.Add("MotherCard()");
                        break;
                    default:
                        item.SubItems.Add("Unknown()");
                        break;
                }
                item.SubItems.Add(info.szCardNo);

                listView_Card.BeginUpdate();
                listView_Card.Items.Insert(0, item);
                if (listView_Card.Items.Count > ListViewCount)
                {
                    listView_Card.Items.RemoveAt(ListViewCount);
                }
                listView_Card.EndUpdate();
            }
        }

        private void GetAllFingerprintInfo()
        {
            listView_Fingerprint.Items.Clear();

            NET_IN_ACCESS_FINGERPRINT_SERVICE_GET stuFingerPrintGetIn = new NET_IN_ACCESS_FINGERPRINT_SERVICE_GET();
            stuFingerPrintGetIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_ACCESS_FINGERPRINT_SERVICE_GET));
            stuFingerPrintGetIn.szUserID = m_UserInfo.szUserID;

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

            bool result = NETClient.OperateAccessFingerprintService(m_LoginID, EM_ACCESS_CTL_FINGERPRINT_SERVICE.GET, pstInParam, pstOutParam, 5000);
            m_FingerprintInfo = (NET_OUT_ACCESS_FINGERPRINT_SERVICE_GET)Marshal.PtrToStructure(pstOutParam, typeof(NET_OUT_ACCESS_FINGERPRINT_SERVICE_GET));

            try
            {
                for (int i = 0; i < m_FingerprintInfo.nRetFingerPrintCount; i++)
                {
                    var item = new ListViewItem();
                    item.Text = (i + 1).ToString();
                    if (m_FingerprintInfo.nDuressIndex == (i + 1))
                    {
                        item.SubItems.Add("Duress()");
                    }
                    else
                    {
                        item.SubItems.Add("Normal()");
                    }

                    listView_Fingerprint.Items.Insert(0, item);
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            finally
            {
                Marshal.FreeHGlobal(stuFingerPrintGetOut.pbyFingerData);
                Marshal.FreeHGlobal(pstInParam);
                Marshal.FreeHGlobal(pstOutParam);
            }
        }

        private void btn_Doors_Click(object sender, EventArgs e)
        {
            if(null == m_UserInfo.nDoors)
            {
                m_UserInfo.nDoors = new int[32];
            }
            List<int> doors = new List<int>();
            for (int i = 0; i < m_UserInfo.nDoorNum; i++)
            {
                doors.Add(m_UserInfo.nDoors[i]);
            }
            DoorSelectForm doorForm = new DoorSelectForm(m_Channel, doors);
            doorForm.ShowDialog();
            if (doorForm.DialogResult == DialogResult.OK)
            {
                var result = doorForm.SelectDoorsList;
                if (result.Count > 0)
                {
                    for (int i = 0; i < result.Count; i++)
                    {
                        m_UserInfo.nDoors[i] = result[i];
                    }
                }
                else
                {
                    m_UserInfo.nDoors = new int[32];
                }
                m_UserInfo.nDoorNum = result.Count;
            }
            doorForm.Dispose();
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

        private void button1_Click(object sender, EventArgs e)
        {
            string cardStr = "";
            if (listView_Card.SelectedIndices != null && listView_Card.SelectedIndices.Count > 0)
            {
                cardStr = listView_Card.SelectedItems[0].SubItems[2].Text;
            }
            else
            {
                MessageBox.Show("Please select a card first!(！)");
                return;
            }

            //
            if (OperateAccess())
            {
                NET_IN_ENCRYPT_STRING inParam = new NET_IN_ENCRYPT_STRING()
                {
                    dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_ENCRYPT_STRING)),
                    szCard = cardStr,
                    szKey = m_Encrypt32,
                    byReserved1 = new byte[3],
                    byReserved2 = new byte[3],
                };

                NET_OUT_ENCRYPT_STRING outParam = new NET_OUT_ENCRYPT_STRING()
                {
                    dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_ENCRYPT_STRING)),
                };

                bool result = NETClient.EncryptString(inParam, ref outParam, 3000);
                if (result)
                {
                    MessageBox.Show(outParam.szEncryptString);
                }
                else
                {
                    MessageBox.Show("Encryption fail" + NETClient.GetLastError());
                }
            }
            else
            {
                MessageBox.Show("Send QRCode decode to device fail" + NETClient.GetLastError());
                return;
            }
        }

        //

        private bool OperateAccess()
        {
            NET_IN_SET_QRCODE_DECODE_INFO inParam = new NET_IN_SET_QRCODE_DECODE_INFO()
            {
                dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_SET_QRCODE_DECODE_INFO)),
                emCipher = NET_ENUM_QRCODE_CIPHER.AES256,
                szKey = m_Encrypt32,
            };

            NET_OUT_SET_QRCODE_DECODE_INFO outParam = new NET_OUT_SET_QRCODE_DECODE_INFO()
            {
                dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_SET_QRCODE_DECODE_INFO))
            };

            IntPtr inPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_IN_SET_QRCODE_DECODE_INFO)));
            IntPtr outPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_OUT_SET_QRCODE_DECODE_INFO)));
            Marshal.StructureToPtr(inParam, inPtr, true);
            Marshal.StructureToPtr(outParam, outPtr, true);
            try
            {
                bool result = NETClient.OperateAccessControlManager(m_LoginID, NET_EM_ACCESS_CTL_MANAGER.SET_QRCODEDECODE_INFO, inPtr, outPtr, 3000);
                if (result)
                {
                    return true;
                }
                else
                {
                    return false;
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                return false;
            }
            finally
            {
                Marshal.FreeHGlobal(inPtr);
                Marshal.FreeHGlobal(outPtr);
            }
        }

        private int AnalyzerDataCallBack(IntPtr lAnalyzerHandle, uint dwEventType, IntPtr pEventInfo, IntPtr pBuffer, uint dwBufSize, IntPtr dwUser, int nSequence, IntPtr reserved)
        {
            if ((uint)EM_EVENT_IVS_TYPE.CITIZEN_PICTURE_COMPARE == dwEventType)
            {
                NET_DEV_EVENT_CITIZEN_PICTURE_COMPARE_INFO info = (NET_DEV_EVENT_CITIZEN_PICTURE_COMPARE_INFO)Marshal.PtrToStructure(pEventInfo, typeof(NET_DEV_EVENT_CITIZEN_PICTURE_COMPARE_INFO));
                m_ImageData = new byte[dwBufSize];
                Marshal.Copy(pBuffer, m_ImageData, 0, (int)dwBufSize);

                this.BeginInvoke(new Action(() =>
                {
                    labelCapturePic.Text = $"Capture successfully! ！";
                    using (MemoryStream stream = new MemoryStream(m_ImageData))
                    {
                        Image image = Image.FromStream(stream);
                        pictureBox_face.Image = image;
                        pictureBox_face.Refresh();
                    }
                }));
            }
            return 0;
        }

        private void BtnCapturePic_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero == m_attachHandler)
            {
                m_analyzerDataCallBack = new fAnalyzerDataCallBack(AnalyzerDataCallBack);
                m_attachHandler = NETClient.RealLoadPicture(m_LoginID, m_Channel - 1, (uint)EM_EVENT_IVS_TYPE.ALL, true, m_analyzerDataCallBack, IntPtr.Zero, IntPtr.Zero);
                if (IntPtr.Zero == m_attachHandler)
                {
                    MessageBox.Show(NETClient.GetLastError());
                    return;
                }
            }

            NET_IN_ACCESSCONTROL_CAPTURE_CMD stIn = new NET_IN_ACCESSCONTROL_CAPTURE_CMD();
            stIn.dwSize = (uint)Marshal.SizeOf(stIn);
            stIn.emGathertype = EM_GATHER_TYPE.EM_GATHER_TYPE_FACE;
            stIn.szUserID = m_UserInfo.szUserID;

            NET_OUT_ACCESSCONTROL_CAPTURE_CMD stOut = new NET_OUT_ACCESSCONTROL_CAPTURE_CMD();
            stOut.dwSize = (uint)Marshal.SizeOf(stOut);

            bool ret = NETClient.AccessControlCaptureCmd(m_LoginID, ref stIn, ref stOut, 3000);
            if (ret)
            {
                labelCapturePic.Text = "Capturing ...(......)";
            }
            else
            {
                labelCapturePic.Text = "Start capture failed()";
            }
        }

        private void txt_SpecialDays_TextChanged(object sender, EventArgs e)
        {

        }
    }

    public enum EM_OperateType
    {
        Add,
        Modify,
    }

}
