namespace AccessDemo2s
{
    partial class UserCardInfoForm
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.cmb_CardType = new System.Windows.Forms.ComboBox();
            this.label6 = new System.Windows.Forms.Label();
            this.txt_CardNum = new System.Windows.Forms.TextBox();
            this.label2 = new System.Windows.Forms.Label();
            this.btn_Confirm = new System.Windows.Forms.Button();
            this.btn_Cancel = new System.Windows.Forms.Button();
            this.btnCaptureCard = new System.Windows.Forms.Button();
            this.labelCapture = new System.Windows.Forms.Label();
            this.SuspendLayout();
            // 
            // cmb_CardType
            // 
            this.cmb_CardType.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_CardType.FormattingEnabled = true;
            this.cmb_CardType.Items.AddRange(new object[] {
            "General()",
            "VIP(VIP)",
            "Guest()",
            "Patrol()",
            "HMDList(HMD)",
            "Duress()",
            "Polling()",
            "MotherCard()"});
            this.cmb_CardType.Location = new System.Drawing.Point(225, 104);
            this.cmb_CardType.Name = "cmb_CardType";
            this.cmb_CardType.Size = new System.Drawing.Size(203, 22);
            this.cmb_CardType.TabIndex = 21;
            // 
            // label6
            // 
            this.label6.AutoSize = true;
            this.label6.Location = new System.Drawing.Point(87, 107);
            this.label6.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label6.Name = "label6";
            this.label6.Size = new System.Drawing.Size(126, 14);
            this.label6.TabIndex = 20;
            this.label6.Text = "CardType():";
            // 
            // txt_CardNum
            // 
            this.txt_CardNum.Location = new System.Drawing.Point(225, 47);
            this.txt_CardNum.Margin = new System.Windows.Forms.Padding(6);
            this.txt_CardNum.Name = "txt_CardNum";
            this.txt_CardNum.Size = new System.Drawing.Size(203, 23);
            this.txt_CardNum.TabIndex = 19;
            // 
            // label2
            // 
            this.label2.AutoSize = true;
            this.label2.Location = new System.Drawing.Point(108, 50);
            this.label2.Margin = new System.Windows.Forms.Padding(6, 0, 6, 0);
            this.label2.Name = "label2";
            this.label2.Size = new System.Drawing.Size(105, 14);
            this.label2.TabIndex = 18;
            this.label2.Text = "CardNo.():";
            // 
            // btn_Confirm
            // 
            this.btn_Confirm.Location = new System.Drawing.Point(295, 229);
            this.btn_Confirm.Name = "btn_Confirm";
            this.btn_Confirm.Size = new System.Drawing.Size(120, 27);
            this.btn_Confirm.TabIndex = 39;
            this.btn_Confirm.Text = "Confirm()";
            this.btn_Confirm.UseVisualStyleBackColor = true;
            this.btn_Confirm.Click += new System.EventHandler(this.btn_Confirm_Click);
            // 
            // btn_Cancel
            // 
            this.btn_Cancel.Location = new System.Drawing.Point(71, 229);
            this.btn_Cancel.Name = "btn_Cancel";
            this.btn_Cancel.Size = new System.Drawing.Size(120, 27);
            this.btn_Cancel.TabIndex = 38;
            this.btn_Cancel.Text = "Canccel()";
            this.btn_Cancel.UseVisualStyleBackColor = true;
            this.btn_Cancel.Click += new System.EventHandler(this.btn_Cancel_Click);
            // 
            // btnCaptureCard
            // 
            this.btnCaptureCard.Location = new System.Drawing.Point(124, 189);
            this.btnCaptureCard.Name = "btnCaptureCard";
            this.btnCaptureCard.Size = new System.Drawing.Size(247, 34);
            this.btnCaptureCard.TabIndex = 40;
            this.btnCaptureCard.Text = "Capture new card()";
            this.btnCaptureCard.UseVisualStyleBackColor = true;
            this.btnCaptureCard.Click += new System.EventHandler(this.BtnCaptureCard_Click);
            // 
            // labelCapture
            // 
            this.labelCapture.AutoSize = true;
            this.labelCapture.Location = new System.Drawing.Point(121, 147);
            this.labelCapture.Name = "labelCapture";
            this.labelCapture.Size = new System.Drawing.Size(0, 14);
            this.labelCapture.TabIndex = 41;
            // 
            // UserCardInfoForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(502, 268);
            this.Controls.Add(this.labelCapture);
            this.Controls.Add(this.btnCaptureCard);
            this.Controls.Add(this.btn_Confirm);
            this.Controls.Add(this.btn_Cancel);
            this.Controls.Add(this.cmb_CardType);
            this.Controls.Add(this.label6);
            this.Controls.Add(this.txt_CardNum);
            this.Controls.Add(this.label2);
            this.Font = new System.Drawing.Font("", 10.5F);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MinimizeBox = false;
            this.Name = "UserCardInfoForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "CardInfo()";
            this.Load += new System.EventHandler(this.UserCardInfoForm_Load);
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        #endregion

        private System.Windows.Forms.ComboBox cmb_CardType;
        private System.Windows.Forms.Label label6;
        private System.Windows.Forms.TextBox txt_CardNum;
        private System.Windows.Forms.Label label2;
        private System.Windows.Forms.Button btn_Confirm;
        private System.Windows.Forms.Button btn_Cancel;
        private System.Windows.Forms.Button btnCaptureCard;
        private System.Windows.Forms.Label labelCapture;
    }
}